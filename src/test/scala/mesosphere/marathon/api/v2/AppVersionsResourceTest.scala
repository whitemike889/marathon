package mesosphere.marathon
package api.v2

import mesosphere.UnitTest
import mesosphere.marathon.api.TestAuthFixture
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{AppDefinition, Timestamp}
import mesosphere.marathon.test.JerseyTest
import scala.concurrent.ExecutionContext.Implicits.global

class AppVersionsResourceTest extends UnitTest with JerseyTest {

  case class Fixture(
      auth: TestAuthFixture = new TestAuthFixture,
      config: MarathonConf = mock[MarathonConf],
      service: MarathonSchedulerService = mock[MarathonSchedulerService],
      groupManager: GroupManager = mock[GroupManager]) {
    val appsVersionsResource = new AppVersionsResource(service, groupManager, auth.auth, auth.auth, config)
  }

  "AppVersionsResourceTest" should {
    "access without authentication is denied" in new Fixture {
      Given("An unauthenticated request")
      auth.authenticated = false
      val req = auth.request

      When("the index is fetched")
      val index = asyncRequest { r => appsVersionsResource.index("appId", req, r) }
      Then("we receive a NotAuthenticated response")
      index.getStatus should be(auth.NotAuthenticatedStatus)

      When("one app version is fetched")
      val show = asyncRequest { r => appsVersionsResource.show("appId", "version", req, r) }
      Then("we receive a NotAuthenticated response")
      show.getStatus should be(auth.NotAuthenticatedStatus)
    }

    "access to index without authorization is denied when the app exists" in new Fixture {
      Given("An unauthenticated request")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request

      groupManager.app("appId".toAbsolutePath) returns Some(AppDefinition("appId".toAbsolutePath, role = "*"))
      When("the index is fetched")
      val index = asyncRequest { r => appsVersionsResource.index("appId", req, r) }
      Then("we receive a not authorized response")
      index.getStatus should be(auth.UnauthorizedStatus)
    }

    "access to index without authorization leads to 404 when the app does not exist" in new Fixture {
      Given("An unauthenticated request")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request

      groupManager.app("appId".toAbsolutePath) returns None
      When("the index is fetched")
      val index = asyncRequest { r => appsVersionsResource.index("appId", req, r) }
      Then("we receive a 404")
      index.getStatus should be(404)
    }

    "access to show without authorization is denied when the app exists" in new Fixture {
      Given("An unauthenticated request")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request

      val version = Timestamp.now()
      service.getApp("appId".toAbsolutePath, version) returns Some(AppDefinition("appId".toAbsolutePath, role = "*"))
      When("one app version is fetched")
      val show = asyncRequest { r => appsVersionsResource.show("appId", version.toString, req, r) }
      Then("we receive a not authorized response")
      show.getStatus should be(auth.UnauthorizedStatus)
    }

    "access to show without authorization leads to a 404 when the app version does not exist" in new Fixture {
      Given("An unauthenticated request")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request

      val version = Timestamp.now()
      service.getApp("appId".toAbsolutePath, version) returns None
      When("one app version is fetched")
      val show = asyncRequest { r => appsVersionsResource.show("appId", version.toString, req, r) }
      Then("we receive a not authorized response")
      show.getStatus should be(404)
    }
  }
}
