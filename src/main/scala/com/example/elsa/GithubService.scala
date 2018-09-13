package com.example.elsa

import akka.actor.{ Actor, ActorLogging, ActorRef, Props, Status }
import akka.http.scaladsl.HttpExt
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{ Authorization, GenericHttpCredentials }
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.pattern.pipe
import akka.stream.Materializer
import spray.json.DefaultJsonProtocol

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global

object GithubService {
  def props(http: HttpExt)(implicit materializer: Materializer): Props = Props(new GithubService(http))

  case class GetUserInfo(username: String, token: String)
  case class UserInfo(username: String, repositories: Seq[RepositoryInfo])
  case class RepositoryInfo(name: String, contributors: Seq[ContributorInfo])
  case class ContributorInfo(name: String, commits: Int)

  case class Repository(name: String, contributors_url: String)
  case class Contributor(login: String, contributions: Int)

  private case class RepositoriesRequest(uri: Uri)
  private case class RepositoriesResponse(response: HttpResponse)
  private case class RepositoriesData(repositories: Seq[Repository], link: Option[HttpHeader])

  private case class ContributorsRequest(repositoryName: String, uri: Uri)
  private case class ContributorsResponse(repositoryName: String, response: HttpResponse)
  private case class ContributorsData(repositoryName: String, contributors: Seq[Contributor], link: Option[HttpHeader])

  private def extractNext(link: HttpHeader): Option[Uri] =
    link.value.split(",")
      .find(p => p.startsWith("<") && p.endsWith(">; rel=next"))
      .map(_.replaceFirst("<", "").replace(">; rel=next", ""))
}

class GithubService(http: HttpExt)(implicit materializer: Materializer) extends Actor with ActorLogging with SprayJsonSupport with DefaultJsonProtocol {
  import GithubService._

  implicit val repositoryFormat = jsonFormat2(Repository)
  implicit val contributorFormat = jsonFormat2(Contributor)

  private var data: mutable.Map[String, mutable.SortedMap[Int, String]] = mutable.Map.empty
  private var pending = 0

  override def receive: Receive = {
    case GetUserInfo(username, token) =>
      log.info(s"GetUserInfo - $username $token")
      val auth = Authorization(GenericHttpCredentials("token", token))
      self ! RepositoriesRequest(s"https://api.github.com/users/$username/repos")
      pending += 1
      context.become(started(auth, username, sender()))
  }

  private def started(auth: Authorization, username: String, replyTo: ActorRef): Receive = {
    case RepositoriesRequest(uri) =>
      log.info(s"RepositoriesRequest - $uri")
      val request = HttpRequest(HttpMethods.GET, uri).withHeaders(auth)
      http.singleRequest(request).map(RepositoriesResponse).pipeTo(self)

    case RepositoriesResponse(response) =>
      log.info(s"RepositoriesResponse - ${response.status}")
      val link = response.headers.find(_.lowercaseName() == "link")
      Unmarshal(response.entity).to[Seq[Repository]].map(RepositoriesData(_, link)).pipeTo(self)

    case RepositoriesData(repositories, link) =>
      log.info(s"RepositoriesData - ${repositories.size}")
      repositories.foreach { repository =>
        self ! ContributorsRequest(repository.name, repository.contributors_url)
        pending += 1
        data += (repository.name -> mutable.SortedMap.empty(Ordering[Int].reverse))
      }
      link.flatMap(extractNext).foreach { next =>
        self ! RepositoriesRequest(next)
        pending += 1
      }
      pending -= 1
      checkPending(username, replyTo)

    case ContributorsRequest(repositoryName, uri) =>
      log.info(s"ContributorsRequest - $repositoryName $uri")
      val request = HttpRequest(HttpMethods.GET, uri).withHeaders(auth)
      http.singleRequest(request).map(ContributorsResponse(repositoryName, _)).pipeTo(self)

    case ContributorsResponse(repositoryName, response) =>
      log.info(s"ContributorsResponse - $repositoryName ${response.status}")
      val link = response.headers.find(_.lowercaseName() == "link")
      Unmarshal(response.entity).to[Seq[Contributor]].map(ContributorsData(repositoryName, _, link)).pipeTo(self)

    case ContributorsData(repositoryName, contributors, link) =>
      log.info(s"ContributorsData - $repositoryName ${contributors.size}")
      contributors.foreach { contributor =>
        data.get(repositoryName).foreach(_ += (contributor.contributions -> contributor.login))
      }
      link.flatMap(extractNext).foreach { next =>
        self ! ContributorsRequest(repositoryName, next)
        pending += 1
      }
      pending -= 1
      checkPending(username, replyTo)

    case Status.Failure(e) => log.error("Failure", e)
  }

  private def checkPending(username: String, replyTo: ActorRef): Unit = {
    log.info(s"pending: $pending")
    if (pending == 0) {
      val repositories = data.map {
        case (k, v) =>
          val contributors = v.map {
            case (commits, name) => ContributorInfo(name, commits)
          }.toSeq
          RepositoryInfo(k, contributors)
      }.toSeq
      replyTo ! UserInfo(username, repositories)
      http.shutdownAllConnectionPools().foreach(_ => context.system.terminate())
    }
  }
}