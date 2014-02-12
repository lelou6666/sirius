/*
 *  Copyright 2012-2014 Comcast Cable Communications Management, LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.comcast.xfinity.sirius.api.impl.membership

import akka.agent.Agent
import akka.event.Logging
import akka.actor._
import com.comcast.xfinity.sirius.api.SiriusConfiguration
import scala.concurrent.duration._
import com.comcast.xfinity.sirius.admin.MonitoringHooks
import scala.collection.immutable.HashMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}
import scala.language.postfixOps

object MembershipActor {

  sealed trait MembershipMessage
  case object GetMembershipData extends MembershipMessage
  case object CheckClusterConfig extends MembershipMessage
  case class Ping(sent: Long) extends MembershipMessage
  case class Pong(pingSent: Long) extends MembershipMessage

  private[membership] object PingMembership

  private[membership] trait MembershipInfoMBean {
    def getMembership: String
    def getMembershipRoundTrip: Map[String, Long]
    def getTimeSinceLastPingUpdate: Map[String, Long]
  }

  /**
   * Create Props for a MembershipActor.
   *
   * @param membershipAgent the Agent[Map[String, Option[ActorRef\]\]\]() to keep updated with the cluster membership
   * @param config SiriusConfiguration for this node
   * @return  Props for creating this actor, which can then be further configured
   *         (e.g. calling `.withDispatcher()` on it)
   */
  def props(membershipAgent: Agent[Map[String, Option[ActorRef]]], config: SiriusConfiguration): Props = {
    val clusterConfigLocation = config.getProp[String](SiriusConfiguration.CLUSTER_CONFIG).getOrElse(
      throw new IllegalArgumentException(SiriusConfiguration.CLUSTER_CONFIG + " is not configured")
    )
    val clusterConfig = BackwardsCompatibleClusterConfig(FileBasedClusterConfig(clusterConfigLocation))
    val checkIntervalSecs = config.getProp(SiriusConfiguration.MEMBERSHIP_CHECK_INTERVAL, 30)
    val pingIntervalSecs = config.getProp(SiriusConfiguration.MEMBERSHIP_PING_INTERVAL, 30)

    Props(classOf[MembershipActor], membershipAgent, clusterConfig, checkIntervalSecs seconds, pingIntervalSecs seconds, config)
  }
}

/**
 * Actor responsible for keeping membership information up to date.
 *
 * For production code you should use MembershipActor#apply instead, this will take care
 * of more proper construction and DI.
 *
 * @param membershipAgent An Agent[Map[String, Option[ActorRef\]\]\] that this actor will keep populated
 *          with the most up to date membership information
 * @param clusterConfig ClusterConfig object containing the membership information
 *          for this cluster
 * @param checkInterval how often to check for updates to clusterConfigPath
 * @param config SiriusConfiguration, used to register monitors
 */
class MembershipActor(membershipAgent: Agent[Map[String, Option[ActorRef]]],
                      clusterConfig: ClusterConfig,
                      checkInterval: FiniteDuration,
                      pingInterval: FiniteDuration,
                      config: SiriusConfiguration)
    extends Actor with MonitoringHooks{
  import MembershipActor._

  val logger = Logging(context.system, "Sirius")

  val configCheckSchedule = context.system.scheduler.schedule(checkInterval, checkInterval, self, CheckClusterConfig)
  val memberPingSchedule = context.system.scheduler.schedule(pingInterval, pingInterval, self, PingMembership)

  var membershipRoundTripMap = HashMap[String, Long]()
  var lastPingUpdateMap = HashMap[String, Long]()

  override def preStart() {
    registerMonitor(new MembershipInfo, config)
    updateMembership()
  }

  override def postStop() {
    configCheckSchedule.cancel()
    unregisterMonitors(config)
  }


  def receive = {
    case CheckClusterConfig =>
      updateMembership()

    case GetMembershipData => sender ! membershipAgent.get()

    case Ping(sent) => sender ! Pong(sent)
    case Pong(pingSent) =>
      val currentTime = System.currentTimeMillis
      val senderPath = sender.path.toString
      membershipRoundTripMap += senderPath -> (currentTime - pingSent)
      lastPingUpdateMap += senderPath -> currentTime

    case PingMembership =>
      val currentTime = System.currentTimeMillis
      membershipAgent.get().values.flatten.foreach(_ ! Ping(currentTime))

    case Terminated(terminated) =>
      membershipAgent send (_ + (terminated.path.toString -> None))
  }

  /**
   * Creates a MembershipMap from the contents of the clusterConfigPath file.
   */
  private[membership] def updateMembership() {
    val actorPaths = clusterConfig.members

    removeMissingPaths(actorPaths)
    updateActorRefs(actorPaths)
  }

  /**
   * If we have any references to actor paths that have been removed from the
   * cluster config, they need to be removed from the agent.
   *
   * @param actorPaths paths for local and remote sirius actors in the cluster
   */
  def removeMissingPaths(actorPaths: List[String]) {
    membershipAgent.get().keys.foreach {
      case key if !actorPaths.contains(key) => membershipAgent send (_ - key)
      case _ =>
    }
  }

  /**
   * For each non-commented line of the ClusterConfig file, try to resolve a remote actor.
   * If the actor is resolved into an actorRef, add it to the membership map. Otherwise, remove
   * any reference to that actor from the map.
   *
   * @param actorPaths list of actor paths to attempt to resolve
   */
  def updateActorRefs(actorPaths: List[String]) {
    val membership = membershipAgent.get()
    actorPaths
      .filter(path => !membership.isDefinedAt(path) || membership(path) == None) // we don't already have a working reference
      .foreach(path => {
        context.actorSelection(path).resolveOne(1 seconds) onComplete {
          case Success(actor) =>
            context.watch(actor)
            membershipAgent send (_ + (path -> Some(actor)))
          case Failure(_) =>
            membershipAgent send (_ + (path -> None))
        }
      })
  }

  class MembershipInfo extends MembershipInfoMBean {
    def getMembership: String = membershipAgent.get().toString()
    def getMembershipRoundTrip: Map[String, Long] = membershipRoundTripMap
    def getTimeSinceLastPingUpdate: Map[String, Long] = {
      val currentTime = System.currentTimeMillis()
      lastPingUpdateMap.foldLeft(HashMap[String, Long]()){
        case (acc, (key, pingTime)) => acc + (key -> (currentTime - pingTime))
      }
    }
  }
}
