package org.sunbird.learner.actors;

import static akka.testkit.JavaTestKit.duration;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.when;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.testkit.javadsl.TestKit;
import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.sunbird.actor.router.RequestRouter;
import org.sunbird.actor.service.SunbirdMWService;
import org.sunbird.actorutil.systemsettings.SystemSettingClient;
import org.sunbird.actorutil.systemsettings.impl.SystemSettingClientImpl;
import org.sunbird.cassandra.CassandraOperation;
import org.sunbird.cassandraimpl.CassandraOperationImpl;
import org.sunbird.common.ElasticSearchUtil;
import org.sunbird.common.exception.ProjectCommonException;
import org.sunbird.common.models.response.Response;
import org.sunbird.common.models.util.ActorOperations;
import org.sunbird.common.models.util.JsonKey;
import org.sunbird.common.request.Request;
import org.sunbird.common.responsecode.ResponseCode;
import org.sunbird.helper.ServiceFactory;
import org.sunbird.learner.actors.tac.UserTnCActor;

@RunWith(PowerMockRunner.class)
@PrepareForTest({
  ServiceFactory.class,
  SystemSettingClientImpl.class,
  RequestRouter.class,
  ElasticSearchUtil.class,
  SunbirdMWService.class
})
@PowerMockIgnore({"javax.management.*", "javax.crypto.*", "javax.net.ssl.*", "javax.security.*"})
public class UserTnCActorTest {
  private static ActorSystem system;

  private static final Props props = Props.create(UserTnCActor.class);
  private static final CassandraOperation cassandraOperation = mock(CassandraOperationImpl.class);;
  public static SystemSettingClient systemSettingClient;
  private static final String LATEST_VERSION = "latestVersion";
  private static final String ACCEPTED_CORRECT_VERSION = "latestVersion";
  private static final String ACCEPTED_INVALID_VERSION = "invalid";

  @BeforeClass
  public static void setUp() {
    system = ActorSystem.create("system");
  }

  @Before
  public void beforeEachTest() {
    PowerMockito.mockStatic(ServiceFactory.class);
    PowerMockito.mockStatic(SystemSettingClientImpl.class);
    PowerMockito.mockStatic(RequestRouter.class);
    systemSettingClient = mock(SystemSettingClientImpl.class);
    when(SystemSettingClientImpl.getInstance()).thenReturn(systemSettingClient);
    ActorRef actorRef = mock(ActorRef.class);
    PowerMockito.mockStatic(ElasticSearchUtil.class);
    PowerMockito.mockStatic(SunbirdMWService.class);
    SunbirdMWService.tellToBGRouter(Mockito.any(), Mockito.any());
    when(RequestRouter.getActor(Mockito.anyString())).thenReturn(actorRef);

    when(ServiceFactory.getInstance()).thenReturn(cassandraOperation);
  }

  @Test
  public void testAcceptUserTcnSuccessWithAcceptFirstTime() {
    when(ElasticSearchUtil.getDataByIdentifier(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(getUser(null));
    Response response =
        setRequest(ACCEPTED_CORRECT_VERSION).expectMsgClass(duration("10 second"), Response.class);
    Assert.assertTrue(
        null != response && "SUCCESS".equals(response.getResult().get(JsonKey.RESPONSE)));
  }

  @Test
  public void testAcceptUserTncSuccessAlreadyAccepted() {
    when(ElasticSearchUtil.getDataByIdentifier(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(getUser(LATEST_VERSION));
    Response response =
        setRequest(ACCEPTED_CORRECT_VERSION).expectMsgClass(duration("10 second"), Response.class);
    Assert.assertTrue(
        null != response && "SUCCESS".equals(response.getResult().get(JsonKey.RESPONSE)));
  }

  @Test
  public void testAcceptUserTncFailureWithInvalidVersion() {
    ProjectCommonException exception =
        setRequest(ACCEPTED_INVALID_VERSION)
            .expectMsgClass(duration("10 second"), ProjectCommonException.class);
    Assert.assertTrue(
        null != exception
            && exception
                .getCode()
                .equalsIgnoreCase(ResponseCode.invalidParameterValue.getErrorCode()));
  }

  private TestKit setRequest(String version) {
    mockTnCSystemSettingResponse();
    mockCassandraOperation();
    Request reqObj = new Request();
    reqObj.setOperation(ActorOperations.USER_TNC_ACCEPT.getValue());
    HashMap<String, Object> innerMap = new HashMap<>();
    innerMap.put(JsonKey.VERSION, version);
    reqObj.setRequest(innerMap);
    TestKit probe = new TestKit(system);
    ActorRef subject = system.actorOf(props);
    subject.tell(reqObj, probe.getRef());
    return probe;
  }

  private void mockCassandraOperation() {
    Response response = new Response();
    response.put(JsonKey.RESPONSE, JsonKey.SUCCESS);
    when(cassandraOperation.updateRecord(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyMap()))
        .thenReturn(response);
  }

  private void mockTnCSystemSettingResponse() {
    when(systemSettingClient.getSystemSettingByFieldAndKey(
            Mockito.any(ActorRef.class),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyObject()))
        .thenReturn(LATEST_VERSION);
  }

  private Map<String, Object> getUser(String lastAcceptedVersion) {
    Map<String, Object> user = new HashMap<>();
    user.put(JsonKey.NAME, "someName");
    if (lastAcceptedVersion != null) {
      user.put(JsonKey.TNC_ACCEPTED_VERSION, lastAcceptedVersion);
    }
    return user;
  }
}
