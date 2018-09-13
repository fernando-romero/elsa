package com.example.elsa

import akka.actor.{ Actor, ActorLogging, ActorSystem, Props, Status, Terminated }
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.headers.{ Authorization, GenericHttpCredentials }
import akka.http.scaladsl.model.{ HttpMethods, HttpRequest, HttpResponse, Uri }
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.{ Http, HttpExt }
import akka.pattern.pipe
import akka.stream.{ ActorMaterializer, Materializer }
import com.typesafe.config.ConfigFactory
import spray.json._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.control.NonFatal

object Main extends App with SprayJsonSupport with DefaultJsonProtocol {
  try {
    val conf = ConfigFactory.load()
    val token = conf.getString("elsa.token")
    val username = "fernando-romero" // args(0)
    val auth = Authorization(GenericHttpCredentials("token", token))

    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()

    //system.actorOf(RepositoryActor.props(Http(), username, auth), name = username)
    val githubService = system.actorOf(GithubService.props(Http()))
    githubService ! GithubService.GetUserInfo(username, token)

  } catch {
    case NonFatal(e) => println(e.getMessage)
  }
}

object Util {
  case class Repository(name: String, contributors_url: String)
  case class Contributor(login: String, contributions: Int)

  def next(response: HttpResponse): Option[Uri] =
    response.headers.find(_.lowercaseName() == "link").flatMap { h =>
      h.value.split(",").find(p => p.startsWith("<") && p.endsWith(">; rel=next")).map { p =>
        p.replaceFirst("<", "").replace(">; rel=next", "")
      }
    }
}

object RepositoryActor {
  def props(http: HttpExt, username: String, auth: Authorization)(implicit materializer: Materializer): Props =
    Props(new RepositoryActor(http, username, auth))
}

class RepositoryActor(
  http: HttpExt,
  username: String,
  auth: Authorization)(implicit materializer: Materializer) extends Actor with ActorLogging with SprayJsonSupport with DefaultJsonProtocol {
  import Util._

  implicit val repositoryFormat = jsonFormat2(Repository)

  self ! Uri(s"https://api.github.com/users/$username/repos")

  override def receive: Receive = started(Nil, done = false)

  private def started(repositories: Seq[Repository], done: Boolean): Receive = {

    case uri: Uri =>
      log.info(s"URI: $uri")
      val request = HttpRequest(HttpMethods.GET, uri).withHeaders(auth)
      http.singleRequest(request).pipeTo(self)

    case response: HttpResponse =>
      log.info(s"RESPONSE: ${response.status}")
      Unmarshal(response.entity).to[Seq[Repository]].pipeTo(self)
      next(response) match {
        case Some(nextUri) => self ! nextUri
        case None => context.become(started(repositories, done = true))
      }

    case newRepositories: Seq[Repository] =>
      log.info(s"NEW REPOSITORIES: ${newRepositories.length}")
      context.become(started(repositories ++ newRepositories, done))
      newRepositories.foreach { repository =>
        val props = ContributorActor.props(http, repository, auth)
        val contributorActor = context.actorOf(props, name = repository.name)
        context.watch(contributorActor)
      }

    case Terminated(_) =>
      log.info(s"WORKKING: ${context.children.size}, DONE: $done")
      if (context.children.isEmpty && done) {
        log.info("TERMINATING...")
        http.shutdownAllConnectionPools().foreach(_ => context.system.terminate())
      }

    case Status.Failure(e) => log.error("FAILURE", e)
  }
}

object ContributorActor {
  def props(http: HttpExt, repository: Util.Repository, auth: Authorization)(implicit materializer: Materializer): Props =
    Props(new ContributorActor(http, repository, auth))
}

class ContributorActor(
  http: HttpExt,
  repository: Util.Repository,
  auth: Authorization)(implicit materializer: Materializer) extends Actor with ActorLogging with SprayJsonSupport with DefaultJsonProtocol {
  import Util._

  implicit val contributorFormat = jsonFormat2(Contributor)

  self ! Uri(repository.contributors_url)

  override def receive: Receive = started(Nil, done = false)

  private def started(contributors: Seq[Contributor], done: Boolean): Receive = {

    case uri: Uri =>
      log.info(s"uri: $uri")
      val request = HttpRequest(HttpMethods.GET, uri).withHeaders(auth)
      http.singleRequest(request).pipeTo(self)

    case response: HttpResponse =>
      log.info(s"response: ${response.status}")
      Unmarshal(response.entity).to[Seq[Contributor]].pipeTo(self)
      Util.next(response) match {
        case Some(nextUri) => self ! nextUri
        case None => context.become(started(contributors, done = true))
      }

    case newContributors: Seq[Contributor] =>
      log.info(s"new contributors: ${newContributors.length}")
      val allContributors = contributors ++ newContributors
      context.become(started(allContributors, done))
      if (done) {
        println(repository.name)
        allContributors.sortBy(_.contributions)(Ordering[Int].reverse).foreach { contributor =>
          println(s"    ${contributor.login} ${contributor.contributions}")
        }
        context.stop(self)
      }

    case Status.Failure(e) => log.error("failure", e)
  }
}