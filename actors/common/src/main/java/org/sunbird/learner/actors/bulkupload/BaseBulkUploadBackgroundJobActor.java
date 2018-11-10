package org.sunbird.learner.actors.bulkupload;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.sunbird.common.Constants;
import org.sunbird.common.exception.ProjectCommonException;
import org.sunbird.common.models.util.BulkUploadJsonKey;
import org.sunbird.common.models.util.JsonKey;
import org.sunbird.common.models.util.LoggerEnum;
import org.sunbird.common.models.util.ProjectLogger;
import org.sunbird.common.models.util.ProjectUtil;
import org.sunbird.common.request.Request;
import org.sunbird.common.responsecode.ResponseCode;
import org.sunbird.common.util.CloudStorageUtil;
import org.sunbird.common.util.CloudStorageUtil.CloudStorageType;
import org.sunbird.learner.actors.bulkupload.model.BulkUploadProcess;
import org.sunbird.learner.actors.bulkupload.model.BulkUploadProcessTask;
import org.sunbird.learner.actors.bulkupload.model.StorageDetails;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.opencsv.CSVWriter;

public abstract class BaseBulkUploadBackgroundJobActor extends BaseBulkUploadActor {

  protected void setSuccessTaskStatus(
      BulkUploadProcessTask task,
      ProjectUtil.BulkProcessStatus status,
      Map<String, Object> row,
      String action)
      throws JsonProcessingException {
    row.put(JsonKey.OPERATION, action);
    task.setSuccessResult(mapper.writeValueAsString(row));
    task.setStatus(status.getValue());
  }

  protected void setTaskStatus(
      BulkUploadProcessTask task,
      ProjectUtil.BulkProcessStatus status,
      String failureMessage,
      Map<String, Object> row,
      String action)
      throws JsonProcessingException {
    row.put(JsonKey.OPERATION, action);
    if (ProjectUtil.BulkProcessStatus.COMPLETED.getValue() == status.getValue()) {
      task.setSuccessResult(mapper.writeValueAsString(row));
      task.setStatus(status.getValue());
    } else if (ProjectUtil.BulkProcessStatus.FAILED.getValue() == status.getValue()) {
      row.put(JsonKey.ERROR_MSG, failureMessage);
      task.setStatus(status.getValue());
      task.setFailureResult(mapper.writeValueAsString(row));
    }
  }

  public void handleBulkUploadBackground(Request request, Function function) {
    String processId = (String) request.get(JsonKey.PROCESS_ID);
    String logMessagePrefix =
        MessageFormat.format(
            "BaseBulkUploadBackGroundJobActor:handleBulkUploadBackground:{0}: ", processId);

    ProjectLogger.log(logMessagePrefix + "called", LoggerEnum.INFO);

    BulkUploadProcess bulkUploadProcess = bulkUploadDao.read(processId);
    if (null == bulkUploadProcess) {
      ProjectLogger.log(logMessagePrefix + "Invalid process ID.", LoggerEnum.ERROR);
      return;
    }

    int status = bulkUploadProcess.getStatus();
    if (!(ProjectUtil.BulkProcessStatus.COMPLETED.getValue() == status)
        || ProjectUtil.BulkProcessStatus.INTERRUPT.getValue() == status) {
      try {
        function.apply(bulkUploadProcess);
      } catch (Exception e) {
        bulkUploadProcess.setStatus(ProjectUtil.BulkProcessStatus.FAILED.getValue());
        bulkUploadProcess.setFailureResult(e.getMessage());
        bulkUploadDao.update(bulkUploadProcess);
        ProjectLogger.log(
            logMessagePrefix + "Exception occurred with error message = " + e.getMessage(), e);
      }
    }

    bulkUploadProcess.setStatus(ProjectUtil.BulkProcessStatus.COMPLETED.getValue());
    bulkUploadDao.update(bulkUploadProcess);
  }

  public void processBulkUpload(
      BulkUploadProcess bulkUploadProcess,
      Function function,
      Map<String, String> supportedColumnMap,
      String[] supportedColumnsOrder) {
    String logMessagePrefix =
        MessageFormat.format(
            "BaseBulkUploadBackGroundJobActor:processBulkUpload:{0}: ", bulkUploadProcess.getId());
    Integer sequence = 0;
    Integer taskCount = bulkUploadProcess.getTaskCount();
    List<Map<String, Object>> successList = new LinkedList<>();
    List<Map<String, Object>> failureList = new LinkedList<>();
    while (sequence <= taskCount) {
      Integer nextSequence = sequence + CASSANDRA_BATCH_SIZE;
      Map<String, Object> queryMap = new HashMap<>();
      queryMap.put(JsonKey.PROCESS_ID, bulkUploadProcess.getId());
      Map<String, Object> sequenceRange = new HashMap<>();
      sequenceRange.put(Constants.GT, sequence);
      sequenceRange.put(Constants.LTE, nextSequence);
      queryMap.put(BulkUploadJsonKey.SEQUENCE_ID, sequenceRange);
      List<BulkUploadProcessTask> tasks = bulkUploadProcessTaskDao.readByPrimaryKeys(queryMap);
      function.apply(tasks);

      try {
        for (BulkUploadProcessTask task : tasks) {

          if (task.getStatus().equals(ProjectUtil.BulkProcessStatus.FAILED.getValue())) {
            failureList.add(
                mapper.readValue(
                    task.getFailureResult(), new TypeReference<Map<String, Object>>() {}));
          } else if (task.getStatus().equals(ProjectUtil.BulkProcessStatus.COMPLETED.getValue())) {
            successList.add(
                mapper.readValue(
                    task.getSuccessResult(), new TypeReference<Map<String, Object>>() {}));
          }
        }

      } catch (IOException e) {
        ProjectLogger.log(
            logMessagePrefix + "Exception occurred with error message = " + e.getMessage(), e);
      }
      performBatchUpdate(tasks);
      sequence = nextSequence;
    }
    setCompletionStatus(
        bulkUploadProcess, successList, failureList, supportedColumnMap, supportedColumnsOrder);
  }

  private void setCompletionStatus(
      BulkUploadProcess bulkUploadProcess,
      List successList,
      List failureList,
      Map supportedColumnMap,
      String[] supportedColumnsOrder) {
    String logMessagePrefix =
        MessageFormat.format(
            "BaseBulkUploadBackGroundJobActor:processBulkUpload:{0}: ", bulkUploadProcess.getId());
    try {
      StorageDetails storageDetails =
          uploadResultToCloud(
              bulkUploadProcess,
              successList,
              failureList,
              supportedColumnMap,
              supportedColumnsOrder);
      ProjectLogger.log(logMessagePrefix + "completed", LoggerEnum.INFO);
      bulkUploadProcess.setSuccessResult(ProjectUtil.convertMapToJsonString(successList));
      bulkUploadProcess.setFailureResult(ProjectUtil.convertMapToJsonString(failureList));
      bulkUploadProcess.setEncryptedStorageDetails(storageDetails);
      bulkUploadProcess.setStatus(ProjectUtil.BulkProcessStatus.COMPLETED.getValue());
    } catch (Exception e) {
      ProjectLogger.log(
          logMessagePrefix + "Exception occurred with error message = " + e.getMessage(), e);
    }
    bulkUploadDao.update(bulkUploadProcess);
  }

  protected void validateMandatoryFields(
      Map<String, Object> csvColumns, BulkUploadProcessTask task, String[] mandatoryFields)
      throws JsonProcessingException {
    if (mandatoryFields != null) {
      for (String field : mandatoryFields) {
        if (StringUtils.isEmpty((String) csvColumns.get(field))) {
          String errorMessage =
              MessageFormat.format(
                  ResponseCode.mandatoryParamsMissing.getErrorMessage(), new Object[] {field});

          setTaskStatus(
              task, ProjectUtil.BulkProcessStatus.FAILED, errorMessage, csvColumns, JsonKey.CREATE);

          ProjectCommonException.throwClientErrorException(
              ResponseCode.mandatoryParamsMissing, errorMessage);
        }
      }
    }
  }

  private StorageDetails uploadResultToCloud(
      BulkUploadProcess bulkUploadProcess,
      List<Map<String, Object>> successList,
      List<Map<String, Object>> failureList,
      Map<String, String> supportedColumnMap,
      String[] supportedColumnsOrder)
      throws IOException {

    String objKey = generateObjectKey(bulkUploadProcess);
    File file = getFileHandle(bulkUploadProcess.getObjectType(), bulkUploadProcess.getId());
    writeResultsToFile(file, successList, failureList, supportedColumnMap, supportedColumnsOrder);
    CloudStorageUtil.upload(
        CloudStorageType.AZURE, bulkUploadProcess.getObjectType(), objKey, file.getAbsolutePath());
    return new StorageDetails(
        CloudStorageType.AZURE.getType(), bulkUploadProcess.getObjectType(), objKey);
  }

  private File getFileHandle(String objType, String processId) {
	  String logMessagePrefix =
		        MessageFormat.format(
		            "BaseBulkUploadBackGroundJobActor:uploadResultToCloud:{0}: ", processId);
    File file = null;
    try {
      file = File.createTempFile(objType, "upload");
    } catch (IOException e) {
      ProjectLogger.log(
    		  logMessagePrefix+ "exception." + e.getMessage(), e);
    }
    return file;
  }

  private String generateObjectKey(BulkUploadProcess bulkUploadProcess) {
    String objType = bulkUploadProcess.getObjectType();
    String processId = bulkUploadProcess.getId();
    return "bulk_upload_" + objType + "_" + processId + ".csv";
  }

  private void writeResultsToFile(
      File file,
      List<Map<String, Object>> successList,
      List<Map<String, Object>> failureList,
      Map<String, String> supportedColumnsMap,
      String[] supportedColumnsOrder)
      throws IOException {
    try (CSVWriter csvWriter = new CSVWriter(new FileWriter(file)); ) {
      List<String> headerRowWithInternalNames =
          new ArrayList<>(Arrays.asList(supportedColumnsOrder));

      headerRowWithInternalNames.add(JsonKey.BULK_UPLOAD_STATUS);
      headerRowWithInternalNames.add(JsonKey.BULK_UPLOAD_ERROR);

      if (MapUtils.isNotEmpty(supportedColumnsMap)) {
        Map<String, String> revMap = getReverseMap(supportedColumnsMap);
        List<String> headerRowWithDisplayNames = new ArrayList<>();
        headerRowWithInternalNames.forEach(
            s -> {
              if (revMap.containsKey(s)) {
                headerRowWithDisplayNames.add(revMap.get(s));
              } else {
                headerRowWithDisplayNames.add(s);
              }
            });
        csvWriter.writeNext(headerRowWithDisplayNames.toArray(new String[0]));
      } else {
        csvWriter.writeNext(headerRowWithInternalNames.toArray(new String[0]));
      }

      addResults(successList, headerRowWithInternalNames, csvWriter);
      addResults(failureList, headerRowWithInternalNames, csvWriter);
      csvWriter.flush();
    }
  }

  private Map<String, String> getReverseMap(Map<String, String> supportedColumnsMap) {
    Map<String, String> revMap = new HashMap<>();
    supportedColumnsMap.forEach(
        (k, v) -> {
          revMap.put(v, k);
        });
    return revMap;
  }

  private void addResults(
      List<Map<String, Object>> resultList, List<String> headerRows, CSVWriter csvWriter) {
    resultList
        .stream()
        .forEach(
            map -> {
              String[] nextLine = new String[headerRows.size()];
              String errMsg = (String) map.get(JsonKey.ERROR_MSG);
              int i = 0;
              for (String field : headerRows) {
                if (JsonKey.BULK_UPLOAD_STATUS.equals(field)) {
                  nextLine[i++] = errMsg == null ? JsonKey.SUCCESS : JsonKey.FAILED;
                } else if (JsonKey.BULK_UPLOAD_ERROR.equals(field)) {
                  nextLine[i++] = errMsg == null ? "" : errMsg;
                } else {
                  nextLine[i++] = String.valueOf(map.get(field));
                }
              }
              csvWriter.writeNext(nextLine);
            });
  }


}
