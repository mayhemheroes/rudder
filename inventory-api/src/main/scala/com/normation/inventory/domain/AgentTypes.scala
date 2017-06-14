/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This file is part of Rudder.
*
* Rudder is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU General Public License version 3, the copyright holders add
* the following Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
* Public License version 3, when you create a Related Module, this
* Related Module is not considered as a part of the work and may be
* distributed under the license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* Rudder is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

*
*************************************************************************************
*/

package com.normation.inventory.domain

import InventoryConstants._
import net.liftweb.common._
import com.normation.utils.HashcodeCaching

/**
 * The enumeration holding the values for the agent
 *
 */
sealed abstract class AgentType {
  def toString : String
  def fullname : String
  // Tag used in fusion inventory ( > 2.3 )
  def tagValue : String
  def toRulesPath : String

  // the name to look for in the inventory to know the agent version
  def inventorySoftwareName: String
  // and a transformation function from reported software version name to agent version name
  def toAgentVersionName(softwareVersionName: String): String

}

object AgentType {

  final case object CfeEnterprise extends AgentType with HashcodeCaching {
    override def toString    = A_NOVA_AGENT
    override def fullname : String = "CFEngine "+this
    override def tagValue = s"cfengine-${A_NOVA_AGENT}".toLowerCase
    override def toRulesPath = "/cfengine-nova"
    override val inventorySoftwareName = "cfengine nova"
    override def toAgentVersionName(softwareVersionName: String) = s"cfe-${softwareVersionName}"
  }

  final case object CfeCommunity extends AgentType with HashcodeCaching {
    override def toString    = A_COMMUNITY_AGENT
    override def fullname : String = "CFEngine "+this
    override def tagValue = s"cfengine-${A_COMMUNITY_AGENT}".toLowerCase
    override def toRulesPath = "/cfengine-community"
    override val inventorySoftwareName = "rudder-agent"
    override def toAgentVersionName(softwareVersionName: String) = softwareVersionName
  }

  final case object Dsc extends AgentType with HashcodeCaching {
    override def toString    = A_DSC_AGENT
    override def fullname : String = "Rudder Windows DSC"
    override def tagValue = "windows-dsc"
    override def toRulesPath = "/dsc"
    override val inventorySoftwareName = "Rudder agent"
    override def toAgentVersionName(softwareVersionName: String) = softwareVersionName+" (dsc)"
  }

  def allValues = CfeEnterprise :: CfeCommunity  :: Dsc :: Nil

  def fromValue(value : String) : Box[AgentType] = {
    // Check if the value is correct compared to the agent tag name (fusion > 2.3) or its toString value (added by CFEngine)
    def checkValue( agent : AgentType) = {
      value.toLowerCase == agent.toString.toLowerCase || value.toLowerCase == agent.tagValue.toLowerCase
    }

    allValues.find(checkValue)  match {
      case None => Failure(s"Wrong type of value for the agent '${value}'")
      case Some(agent) => Full(agent)
    }
  }
}

/*
 * Version of the agent
 */
final case class AgentVersion(value: String)

final case class AgentInfo(
    agentType   : AgentType
    //for now, the version must be an option, because we don't add it in the inventory
    //and must try to find it from packages
  , version       : Option[AgentVersion]
  , securityToken : SecurityToken
)

object AgentInfoSerialisation {
  import net.liftweb.json.JsonDSL._
  import AgentType._

  import net.liftweb.json._

  implicit class ToJson(agent: AgentInfo) {

    def toJsonString =
      compactRender(
          ("agentType" -> agent.agentType.toString())
        ~ ("version"   -> agent.version.map( _.value ))
        ~ ("securityToken" ->
              ("value" -> agent.securityToken.key)
            ~ ("type"  -> SecurityToken.kind(agent.securityToken))
          )
      )
  }

  def parseSecurityToken(agentType : AgentType, tokenJson: JValue, tokenDefault : Option[String]) : Box[SecurityToken]= {
    import net.liftweb.json.compactRender

    def extractValue(tokenJson : JValue) = {
      tokenJson \ "value" match {
        case JString(s) => Some(s)
        case _ => None
      }
    }

    agentType match {
      case Dsc => tokenJson \ "type" match {
        case JString(Certificate.kind) => extractValue(tokenJson) match {
          case Some(token) => Full(Certificate(token))
          case None => Failure("No value defined for security token")
        }
        case JString(PublicKey.kind) => Failure("Cannot have a public Key for dsc agent, only a certificate is valid")
        case JNothing => Failure("No value define for security token")
        case invalidJson => Failure(s"Invalid value for security token, ${compactRender(invalidJson)}")
      }
      case _ => tokenJson \ "type" match {
        case JString(Certificate.kind) => extractValue(tokenJson) match {
          case Some(token) => Full(Certificate(token))
          case None => Failure("No value defined for security token")
        }
        case JString(PublicKey.kind) => extractValue(tokenJson) match {
          case Some(token) => Full(PublicKey(token))
          case None => Failure("No value defined for security token")
        }
        case invalidJson =>
          tokenDefault match {
            case Some(default) => Full(PublicKey(default))
            case None => Failure(s"Invalid value for security token, ${compactRender(invalidJson)}, and no public key were stored")
          }
      }
    }
  }

  /*
   * Retrieve the agent information from JSON. "agentType" is mandatory,
   * but version isn't, and even if we don't parse it correctly, we
   * successfully return an agent (without version).
   */
  def parseJson(s: String, optToken : Option[String]): Box[AgentInfo] = {
    for {
      json <- try { Full(parse(s)) } catch { case ex: Exception => Failure(s"Can not parse agent info: ${ex.getMessage }", Full(ex), Empty) }
      agentType <- (json \ "agentType") match {
                     case JString(tpe) => AgentType.fromValue(tpe)
                     case JNothing => Failure("No value defined for security token")
                     case invalidJson  => Failure(s"Error when trying to parse string as JSON Agent Info (missing required field 'agentType'): ${compactRender(invalidJson)}")
                   }
     agentVersion = json \ "version" match {
                      case JString(version) => Some(AgentVersion(version))
                      case _                => None
                    }
     token <- json \ "securityToken" match {
                case JObject(json) => parseSecurityToken(agentType, json, optToken)
                case _             => parseSecurityToken(agentType, JNothing, optToken)
              }

    } yield {
      AgentInfo(agentType, agentVersion, token)
    }
  }

  /*
   * Parsing agent must be done in two steps for compat with old versions:
   * - try to parse in json: if ok, we have the new version
   * - else, try to parse in old format, put None to version.
   */
  def parseCompatNonJson(s: String, optToken : Option[String]): Box[AgentInfo] = {
    parseJson(s, optToken).or(
      for {
        agentType <- AgentType.fromValue(s) ?~! (
             s"Error when mapping '${s}' to an agent info. We are expecting either a json like "+
             s"{'agentType': type, 'version': opt_version}, or an agentType with allowed values in ${AgentType.allValues.mkString(", ")}"
               )
        token  <- parseSecurityToken(agentType, JNothing, optToken)
      } yield {
        AgentInfo(agentType, None, token)
      }
    )
  }
}
