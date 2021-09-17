package org.sunbird.workallocation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ser.FilterProvider;
import com.fasterxml.jackson.databind.ser.impl.SimpleBeanPropertyFilter;
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.TopicPartition;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.sunbird.common.service.OutboundRequestHandlerServiceImpl;
import org.sunbird.common.util.CbExtServerProperties;
import org.sunbird.core.logger.CbExtLogger;
import org.sunbird.core.producer.Producer;
import org.sunbird.workallocation.model.*;
import org.sunbird.workallocation.model.telemetryEvent.*;
import org.sunbird.workallocation.repo.WorkAllocationRepo;
import org.sunbird.workallocation.repo.WorkOrderRepo;
import org.sunbird.workallocation.repo.UserWorkAllocationMappingRepo;
import org.sunbird.workallocation.util.WorkAllocationConstants;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

@Service
public class WATConsumer {

    @Autowired
    private WorkOrderRepo workOrderRepo;

    @Autowired
    private WorkAllocationRepo workAllocationRepo;

    @Autowired
    private OutboundRequestHandlerServiceImpl outboundRequestHandlerService;

    @Autowired
    private CbExtServerProperties cbExtServerProperties;

    @Autowired
    private Producer producer;

    @Autowired
    private UserWorkAllocationMappingRepo userWorkAllocationMappingRepo;

    @Value("${kafka.topics.parent.telemetry.event}")
    public String telemetryEventTopicName;

    private CbExtLogger logger = new CbExtLogger(getClass().getName());

    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-ddXHH:mm:ss.ms", Locale.getDefault());

    private static final String[] ignorableFieldsForPublishedState = {"userName", "userEmail", "submittedFromName", "submittedFromEmail", "submittedToName", "submittedToEmail", "createdByName", "updatedByName"};

    @KafkaListener(id = "id2", groupId = "watTelemetryTopic-consumer", topicPartitions = {
            @TopicPartition(topic = "${kafka.topics.wat.telemetry.event}", partitions = {"0", "1", "2", "3"})})
    public void processMessage(ConsumerRecord<String, String> data) {
        try {
            logger.info("Consuming the audit records for WAT .....");
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> workAllocationObj = mapper.readValue(String.valueOf(data.value()), Map.class);
            WorkOrderPrimaryKeyModel primaryKeyModel = new WorkOrderPrimaryKeyModel();
            primaryKeyModel.setId((String) workAllocationObj.get("workorderId"));
            Optional<WorkOrderCassandraModel> workOrderCassandraModelOptional = workOrderRepo.findById(primaryKeyModel);
            if (workOrderCassandraModelOptional.isPresent()) {
                WorkOrderCassandraModel workOrderCassandraModel = workOrderCassandraModelOptional.get();
                Map<String, Object> watObj = mapper.readValue(workOrderCassandraModel.getData(), Map.class);
                logger.info("consumed record for WAT ...");
                logger.info(mapper.writeValueAsString(watObj));
                List<String> userIds = (List<String>) watObj.get("userIds");
                if (!CollectionUtils.isEmpty(userIds)) {
                    List<WorkAllocationCassandraModel> workAllocationList = workAllocationRepo.findByIdIn(userIds);
                    List<WorkAllocationDTOV2> workAllocations = new ArrayList<>();
                    workAllocationList.forEach(workAllocationCassandraModel -> {
                        try {
                            workAllocations.add(mapper.readValue(workAllocationCassandraModel.getData(), WorkAllocationDTOV2.class));
                        } catch (IOException e) {
                            logger.error(e);
                        }
                    });
                    watObj.put("users", workAllocations);

                    //update the user_workorder_mapping table
                    updateUserWorkOrderMappings(watObj, workAllocations);
                }
                watObj = getFilterObject(watObj);
                Event event = getTelemetryEvent(watObj);
                logger.info("Posting WAT event to telemetry ...");
                logger.info(mapper.writeValueAsString(event));
                //postTelemetryEvent(event);
                producer.push(telemetryEventTopicName, event);
            }
        } catch (IOException e) {
            logger.error(e);
        }
    }

    private Map<String, Object> getFilterObject(Map<String, Object> watObj) throws IOException {
        ObjectMapper mapper1 = new ObjectMapper();
        mapper1.addMixIn(Object.class, PropertyFilterMixIn.class);
        FilterProvider filters = new SimpleFilterProvider().addFilter("PropertyFilter", SimpleBeanPropertyFilter.serializeAllExcept(ignorableFieldsForPublishedState));
        String writer = mapper1.writer(filters).writeValueAsString(watObj);
        watObj = mapper1.readValue(writer, Map.class);
        return watObj;
    }

    private void postTelemetryEvent(Event event) {
        outboundRequestHandlerService.fetchResultUsingPost(cbExtServerProperties.getTelemetryBaseUrl() + cbExtServerProperties.getTelemetryEndpoint(),
                event);
    }

    private Event getTelemetryEvent(Map<String, Object> watObject) {
        HashMap<String, Object> eData = new HashMap<>();
        eData.put("state", watObject.get("status"));
        eData.put("props", WorkAllocationConstants.PROPS);
        HashMap<String, Object> cbObject = new HashMap<>();
        cbObject.put("id", watObject.get("id"));
        cbObject.put("type", WorkAllocationConstants.TYPE);
        cbObject.put("ver", String.valueOf(1.0));
        cbObject.put("name", watObject.get("name"));
        cbObject.put("org", watObject.get("deptName"));
        eData.put("cb_object", cbObject);
        HashMap<String, Object> data = new HashMap<>();
        data.put("data", watObject);
        eData.put("cb_data", data);
        Event event = new Event();
        Actor actor = new Actor();
        actor.setId((String) watObject.get("id"));
        actor.setType(WorkAllocationConstants.USER_CONST);
        event.setActor(actor);
        event.setEid(WorkAllocationConstants.EID);
        event.setEdata(eData);
        event.setVer(WorkAllocationConstants.VERSION);
        event.setEts((Long) watObject.get("updatedAt"));
        event.setMid(WorkAllocationConstants.CB_NAME + "." + UUID.randomUUID());
        Context context = new Context();
        context.setChannel((String) watObject.get("deptId"));
        context.setEnv(WorkAllocationConstants.WAT_NAME);
        Pdata pdata = new Pdata();
        pdata.setId(cbExtServerProperties.getWatTelemetryEnv());
        pdata.setPid(WorkAllocationConstants.MDO_NAME_CONST);
        pdata.setVer(WorkAllocationConstants.VERSION_TYPE);
        context.setPdata(pdata);
        event.setContext(context);
        ObjectData objectData = new ObjectData();
        objectData.setId((String) watObject.get("id"));
        objectData.setType(WorkAllocationConstants.WORK_ORDER_ID_CONST);
        event.setObject(objectData);
//      event.setType(WorkAllocationConstants.EVENTS_NAME);
        return event;
    }

    public void updateUserWorkOrderMappings(Map<String, Object> workOrderMap, List<WorkAllocationDTOV2> workAllocationDTOV2List) {
        try {
            String workOrderId = (String) workOrderMap.get("id");
            String status = (String) workOrderMap.get("status");
            if (!CollectionUtils.isEmpty(workAllocationDTOV2List) && !StringUtils.isEmpty(status)) {
                List<UserWorkAllocationMapping> userWorkAllocationMappings = new ArrayList<>();
                workAllocationDTOV2List.forEach(workAllocationDTOV2 -> {
                    if (!StringUtils.isEmpty(workAllocationDTOV2.getUserId()) && !StringUtils.isEmpty(workAllocationDTOV2.getId())) {
                        UserWorkAllocationMapping userWorkAllocationMapping = new UserWorkAllocationMapping(workAllocationDTOV2.getUserId(), workAllocationDTOV2.getId(), workOrderId, status);
                        userWorkAllocationMappings.add(userWorkAllocationMapping);
                    }
                });
                userWorkAllocationMappingRepo.saveAll(userWorkAllocationMappings);
            }
        } catch (Exception ex) {
            logger.error(ex);
        }
    }

}
