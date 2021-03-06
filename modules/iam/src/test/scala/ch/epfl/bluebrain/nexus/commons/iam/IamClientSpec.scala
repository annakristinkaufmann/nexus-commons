package ch.epfl.bluebrain.nexus.commons.iam

import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpMessage.DiscardedEntity
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import akka.stream.{ActorMaterializer, Materializer}
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.commons.http.HttpClient
import ch.epfl.bluebrain.nexus.commons.http.HttpClient.UntypedHttpClient
import ch.epfl.bluebrain.nexus.commons.iam.IamClientSpec._
import ch.epfl.bluebrain.nexus.commons.iam.acls.Permission.{Own, Read, Write}
import ch.epfl.bluebrain.nexus.commons.iam.acls._
import ch.epfl.bluebrain.nexus.commons.iam.auth.{AuthenticatedUser, User}
import ch.epfl.bluebrain.nexus.commons.iam.identity.Caller.{AnonymousCaller, AuthenticatedCaller, _}
import ch.epfl.bluebrain.nexus.commons.iam.identity.Identity.{AuthenticatedRef, GroupRef, UserRef}
import ch.epfl.bluebrain.nexus.commons.types.HttpRejection.UnauthorizedAccess
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import _root_.io.circe.Encoder
import _root_.io.circe.generic.extras.Configuration
import _root_.io.circe.generic.extras.auto._
import _root_.io.circe.syntax._
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfter, Matchers, WordSpecLike}

import scala.concurrent.Future
import scala.concurrent.duration._

class IamClientSpec
    extends TestKit(ActorSystem("IamClientSpec"))
    with WordSpecLike
    with Matchers
    with ScalaFutures
    with BeforeAndAfter {

  private implicit val config                = Configuration.default.withDiscriminator("type")
  private implicit val ec                    = system.dispatcher
  private implicit val mt: ActorMaterializer = ActorMaterializer()
  private implicit val iamUri                = IamUri("http://localhost:8080")
  private val credentials                    = OAuth2BearerToken(ValidToken)
  private val authUser: User = AuthenticatedUser(
    Set(GroupRef("BBP", "group1"), GroupRef("BBP", "group2"), UserRef("realm", "f:someUUID:username")))
  private val authUserWithFilteredGroups: User = AuthenticatedUser(
    Set(GroupRef("BBP", "group1"), UserRef("realm", "f:someUUID:username")))

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(5 seconds, 200 milliseconds)

  "An IamClient" should {

    "return unathorized whenever the token is wrong" in {
      implicit val cl         = fixedClient[None.type](uriFor("/oauth2/user", Query("filterGroups" -> "false")))
      implicit val httpClient = HttpClient.withAkkaUnmarshaller[User]
      val response            = IamClient().getCaller(Some(OAuth2BearerToken("invalidToken")))
      ScalaFutures.whenReady(response.failed, Timeout(patienceConfig.timeout)) { e =>
        e shouldBe a[UnauthorizedAccess.type]
      }
    }

    "return anonymous caller whenever there is no token provided" in {
      implicit val cl         = fixedClient[None.type](uriFor("/shouldnt/even/call/iam"))
      implicit val httpClient = HttpClient.withAkkaUnmarshaller[User]
      IamClient().getCaller(None).futureValue shouldEqual AnonymousCaller()
    }

    "return an authenticated caller whenever the token provided is correct" in {
      implicit val cl         = fixedClient(uriFor("/oauth2/user", Query("filterGroups" -> "false")), authA = Some(authUser))
      implicit val httpClient = HttpClient.withAkkaUnmarshaller[User]

      IamClient().getCaller(Some(credentials)).futureValue shouldEqual AuthenticatedCaller(credentials, authUser)
    }

    "return an authenticated caller with filtered groups" in {
      implicit val cl =
        fixedClient(uriFor("/oauth2/user", Query("filterGroups" -> "true")), authA = Some(authUserWithFilteredGroups))
      implicit val httpClient = HttpClient.withAkkaUnmarshaller[User]

      IamClient().getCaller(Some(credentials), filterGroups = true).futureValue shouldEqual AuthenticatedCaller(
        credentials,
        authUserWithFilteredGroups)
    }

    "return expected acls whenever the caller is authenticated" in {
      val aclAuth = FullAccessControlList(
        (GroupRef("BBP", "group1"), Path("/acls/prefix/some/resource/one"), Permissions(Own, Read, Write)))
      implicit val cl =
        fixedClient(uriFor("/acls/prefix/some/resource/one", Query("parents" -> "false", "self" -> "true")),
                    authA = Some(aclAuth))
      implicit val httpClient = HttpClient.withAkkaUnmarshaller[FullAccessControlList]

      implicit val caller = AuthenticatedCaller(credentials, authUser)
      IamClient().getAcls(Path("/prefix/some/resource/one"), self = true).futureValue shouldEqual aclAuth
    }
    "return expected acls whenever the caller is anonymous" in {
      val aclAnon =
        FullAccessControlList((AuthenticatedRef(None), Path("/acls/prefix/some/resource/two"), Permissions(Read)))
      implicit val cl =
        fixedClient(uriFor("/acls/prefix/some/resource/two", Query("parents" -> "false", "self" -> "false")),
                    anonA = Some(aclAnon))
      implicit val httpClient = HttpClient.withAkkaUnmarshaller[FullAccessControlList]
      implicit val anonCaller = AnonymousCaller()

      IamClient().getAcls(Path("///prefix/some/resource/two")).futureValue shouldEqual aclAnon
    }
  }

  private def uriFor(path: String, query: Query = Query.Empty) = {
    iamUri.value.withPath(Uri.Path(path)).withQuery(query)
  }
}

object IamClientSpec {

  val ValidToken = "validToken"

  def fixedClient[A](expectedUri: Uri, anonA: Option[A] = None, authA: Option[A] = None)(
      implicit mt: Materializer,
      E: Encoder[A]): UntypedHttpClient[Future] =
    new UntypedHttpClient[Future] {
      override def apply(req: HttpRequest): Future[HttpResponse] =
        req
          .header[Authorization]
          .collect {
            case Authorization(OAuth2BearerToken(ValidToken)) if expectedUri == req.uri =>
              responseOrEmpty(authA)
            case Authorization(OAuth2BearerToken(_)) if expectedUri == req.uri =>
              Future.successful(
                HttpResponse(
                  entity = HttpEntity(
                    ContentTypes.`application/json`,
                    """{"code" : "UnauthorizedCaller", "description" : "The caller is not permitted to perform this request"}"""),
                  status = StatusCodes.Unauthorized
                ))
            case _ =>
              responseOrEmpty(authA)

          }
          .getOrElse {
            if (expectedUri == req.uri)
              responseOrEmpty(anonA)
            else
              Future.failed(new RuntimeException(s"Wrong uri ${req.uri}"))
          }

      override def discardBytes(entity: HttpEntity): Future[DiscardedEntity] = Future.successful(entity.discardBytes())

      override def toString(entity: HttpEntity): Future[String] = Future.successful("")

      private def responseOrEmpty(entity: Option[A])(implicit E: Encoder[A]) = {
        Future.successful(
          entity
            .map(e => HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, e.asJson.noSpaces)))
            .getOrElse(HttpResponse()))
      }
    }
}
