/**
 * This file is part of agora-mixnet.
 * Copyright (C) 2015-2016  Agora Voting SL <agora@agoravoting.com>

 * agora-mixnet is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License.

 * agora-mixnet is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.

 * You should have received a copy of the GNU Affero General Public License
 * along with agora-mixnet.  If not, see <http://www.gnu.org/licenses/>.
**/

package accumulator

import app._
import models._
import play.api.libs.json._
import scala.concurrent.{Future, Promise}
import scala.util.{Try, Success, Failure}
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import akka.http.scaladsl.model._
import utils._
import election.JsElection
import election.ElectionJsonFormatter

trait PostOffice extends ElectionJsonFormatter with Response with ErrorProcessing
{  
  implicit val system = ActorSystem()
  implicit val executor = system.dispatchers.lookup("my-other-dispatcher")
  implicit val materializer = ActorMaterializer()
  // post index counter
  private var index : Long = 0
  private val queue = scala.collection.mutable.Queue[Option[Post]]()
  // the first parameter is the uid
  private val electionMap = scala.collection.mutable.Map[Long, MaintainerWrapper]()
  // list of callbacks to be called when a new election is created
  private val callbackQueue = scala.collection.mutable.Queue[String => Unit]()

  def getElectionInfo(electionId: Long) : Future[HttpResponse] = {
    val promise = Promise[HttpResponse]()
    Future {
      electionMap.get(electionId) match {
        case Some(electionWrapper) =>
           promise.success(HttpResponse(status = 200, entity = Json.stringify(response( electionWrapper.getElectionInfo() )) ))
         
        case None =>
          promise.success(HttpResponse(status = 400, entity = Json.stringify(error(s"Election $electionId not found", ErrorCodes.EO_ERROR)) ))
      }
    } recover { case err => 
      promise.trySuccess(HttpResponse(status = 400, entity = Json.stringify(error(getMessageFromThrowable(err), ErrorCodes.EO_ERROR)) ))
    }
    promise.future
  }
  
   def getResults(electionId: Long) : Future[HttpResponse] = {
     val promise = Promise[HttpResponse]()
     Future {
      electionMap.get(electionId) match {
        case Some(electionWrapper) =>
          electionWrapper.getResults() match {
            case Some(results) =>
              promise.success(HttpResponse(status = 200, entity = Json.stringify(response( results )) ))
            case None =>
              promise.success(HttpResponse(status = 400, entity = Json.stringify(error(s"Election $electionId has no results yet", ErrorCodes.EO_ERROR)) ))
          }
          
        case None =>
          promise.success(HttpResponse(status = 400, entity = Json.stringify(error(s"Election $electionId not found", ErrorCodes.EO_ERROR)) ))
      }
    } recover { case err => 
      promise.trySuccess(HttpResponse(status = 400, entity = Json.stringify(error(getMessageFromThrowable(err), ErrorCodes.EO_ERROR)) ))
    }
    promise.future
  }
  
  def add(post: Post) {
    println("GG PostOffice::add")
    queue.synchronized {
      Try {
        post.board_attributes.index.toLong
      } map { postIndex =>
        if(postIndex < index) {
          println("Error: old post")
        } else if(postIndex >= index) {
          if(postIndex < index + queue.size) {
            queue.get((postIndex - index).toInt) map { x =>
              x match {
                case Some(p) =>
                  println("Error: duplicated post")
                case None =>
                  queue.update((postIndex - index).toInt, Some(post))
              }
            }
          } else {
            queue ++= List.fill((postIndex - (index + (queue.size).toLong)).toInt)(None)
            queue += Some(post)
          }
        }
      }
    }
    remove()
  }
  
  private def send(post: Post) {
    println("send post index: " + post.board_attributes.index)
    if("election" == post.user_attributes.section) {
      val group : String = post.user_attributes.group
      if("create" == group) {
        electionMap.synchronized {
          val jsMsg = Json.parse(post.message)
          jsMsg.validate[JsElection] match {
            case jSeqPost: JsSuccess[JsElection] =>
              val electionIdStr = jSeqPost.get.state.id
              Try { electionIdStr.toLong } match {
                case Success(electionId) =>
                  val maintainer = new MaintainerWrapper(jSeqPost.get.level, electionIdStr)
                  maintainer.push(post)
                  electionMap += (electionId -> maintainer)
                  callbackQueue.synchronized {
                    callbackQueue foreach { func =>
                      Future { func(electionIdStr) }
                    }
                  }
                case Failure(e) =>
                  println(s"Error: Election Id is not a number (but It should be): ${electionIdStr}")
              }
            case e: JsError => 
              println("Error: JsCreate format error")
          }
        }
      } else {
        Try { group.toLong } match {
          case Success(electionId) =>
            electionMap.synchronized {
              electionMap.get(electionId) 
            } match {
              case Some(electionWrapper) => 
                electionWrapper.push(post)
              case None =>
                println(s"Error: Election Id not found in db: ${electionId}, post is: " + post.toString())
            }
            
          case Failure(e) => 
            println(s"Error: group is not a number : ${group}")
        }
      }
    } else {
      println("Error: post is not an election")
    }
  }
  
  private def getQueueHeadOpt() :Option[Post] = {
    queue.synchronized {
      if(queue.size > 0) {
        queue.head match {
          case Some(post) =>
            // TODO: here we should check the post hash and signature
            index = index + 1
            queue.dequeue
          case None =>
            None
        }
      } else {
        None
      }
    }
  }
  
  private def remove() {
    println("GG PostOffice::remove")
    var head = getQueueHeadOpt()
    while (head != None) {
      send(head.get)
      head = getQueueHeadOpt()
    }
  }
  
  def getSubscriber(uid : String) = {
    Try { uid.toLong } match {
      case Success(electionId) =>
        electionMap.get(electionId) match {
          case Some(electionWrapper) => 
            electionWrapper.getSubscriber()
          case None =>
            throw new scala.Error(s"Error subscribing: Election Id not found in db: ${electionId}")
        }
      case Failure(e) =>
        throw new scala.Error(s"Error subscribing: Election id is not a number: {uid}")
    }
  }
  
  def addElectionCreationListener(callback: (String) => Unit) {
    callbackQueue.synchronized {
      callbackQueue += callback
    }
  }
}

