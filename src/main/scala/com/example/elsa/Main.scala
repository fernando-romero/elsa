package com.example.elsa

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import spray.json._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{ Failure, Success }

object Main extends App with SprayJsonSupport with DefaultJsonProtocol {
  try {
    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()
    implicit val timeout: Timeout = 5.seconds

    implicit val contributorInfoFormat = jsonFormat2(GithubService.ContributorInfo)
    implicit val repositoryInfoFormat = jsonFormat2(GithubService.RepositoryInfo)
    implicit val userInfoFormat = jsonFormat2(GithubService.UserInfo)

    val conf = ConfigFactory.load()
    val token = conf.getString("elsa.token")
    val username = args(0)
    val http = Http()

    val githubService = system.actorOf(GithubService.props(http))
    (githubService ? GithubService.GetUserInfo(username, token)).mapTo[GithubService.UserInfo].onComplete {
      case Success(userInfo) => println(userInfo.toJson.prettyPrint)
      case Failure(e) => println(e.getMessage)
    }
  } catch {
    case NonFatal(e) => println(e.getMessage)
  }
}
