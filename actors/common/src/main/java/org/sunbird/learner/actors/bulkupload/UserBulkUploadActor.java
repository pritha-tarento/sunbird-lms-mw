package org.sunbird.learner.actors.bulkupload;

import com.fasterxml.jackson.core.type.TypeReference;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.sunbird.actor.router.ActorConfig;
import org.sunbird.actorutil.systemsettings.SystemSettingClient;
import org.sunbird.actorutil.systemsettings.impl.SystemSettingClientImpl;
import org.sunbird.common.models.util.ActorOperations;
import org.sunbird.common.models.util.BulkUploadActorOperation;
import org.sunbird.common.models.util.JsonKey;
import org.sunbird.common.models.util.TelemetryEnvKey;
import org.sunbird.common.request.ExecutionContext;
import org.sunbird.common.request.Request;
import org.sunbird.learner.actors.bulkupload.model.BulkUploadProcess;
import org.sunbird.learner.util.Util;

@ActorConfig(
  tasks = {"userBulkUpload"},
  asyncTasks = {}
)
public class UserBulkUploadActor extends BaseBulkUploadActor {
  private SystemSettingClient systemSettingClient = new SystemSettingClientImpl();
  private String[] bulkUserAllowedFields = {
    JsonKey.FIRST_NAME,
    JsonKey.LAST_NAME,
    JsonKey.PHONE,
    JsonKey.COUNTRY_CODE,
    JsonKey.EMAIL,
    JsonKey.USERNAME,
    JsonKey.PHONE_VERIFIED,
    JsonKey.EMAIL_VERIFIED,
    JsonKey.ROLES,
    JsonKey.POSITION,
    JsonKey.GRADE,
    JsonKey.LOCATION,
    JsonKey.DOB,
    JsonKey.GENDER,
    JsonKey.LANGUAGE,
    JsonKey.PROFILE_SUMMARY,
    JsonKey.SUBJECT,
    JsonKey.WEB_PAGES,
    JsonKey.EXTERNAL_ID_PROVIDER,
    JsonKey.EXTERNAL_ID,
    JsonKey.EXTERNAL_ID_TYPE,
    JsonKey.EXTERNAL_IDS,
    JsonKey.USER_ID,
    JsonKey.ORG_ID
  };

  @Override
  public void onReceive(Request request) throws Throwable {
    Util.initializeContext(request, TelemetryEnvKey.USER);
    ExecutionContext.setRequestId(request.getRequestId());
    String operation = request.getOperation();
    if (operation.equalsIgnoreCase("userBulkUpload")) {
      upload(request);
    } else {
      onReceiveUnsupportedOperation("UserBulkUploadActor");
    }
  }

  private void upload(Request request) throws IOException {
    Map<String, Object> req = (Map<String, Object>) request.getRequest().get(JsonKey.DATA);
    Object dataObject =
        systemSettingClient.getSystemSettingByFieldAndKey(
            getActorRef(ActorOperations.GET_SYSTEM_SETTING.getValue()),
            "userProfileConfig",
            "csv.supportedColumns",
            new TypeReference<Map>() {});
    Map<String, Object> supportedColumnsMap = null;
    if (dataObject != null) {
      supportedColumnsMap = (Map<String, Object>) dataObject;
      List<String> supportedColumnsList = new ArrayList<>();
      supportedColumnsMap.forEach((key, value) -> supportedColumnsList.add(key));
      validateFileHeaderFields(
          req, supportedColumnsList.toArray(new String[supportedColumnsList.size()]), false);
    } else {
      validateFileHeaderFields(req, bulkUserAllowedFields, false);
    }
    BulkUploadProcess bulkUploadProcess =
        handleUpload(JsonKey.USER, (String) req.get(JsonKey.CREATED_BY));
    processUserBulkUpload(req, bulkUploadProcess.getId(), bulkUploadProcess, supportedColumnsMap);
  }

  private void processUserBulkUpload(
      Map<String, Object> req,
      String processId,
      BulkUploadProcess bulkUploadProcess,
      Map supportedColumnsMap)
      throws IOException {
    byte[] fileByteArray = null;
    if (null != req.get(JsonKey.FILE)) {
      fileByteArray = (byte[]) req.get(JsonKey.FILE);
    }
    Integer recordCount =
        validateAndParseRecords(
            fileByteArray, processId, new HashMap(), supportedColumnsMap, false);
    processBulkUpload(
        recordCount,
        processId,
        bulkUploadProcess,
        BulkUploadActorOperation.USER_BULK_UPLOAD_BACKGROUND_JOB.getValue(),
        bulkUserAllowedFields);
  }
}
