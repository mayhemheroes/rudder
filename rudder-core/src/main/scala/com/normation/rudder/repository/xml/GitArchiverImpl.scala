/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.rudder.repository.xml

import java.io.File
import scala.xml.PrettyPrinter
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.api.Git
import org.joda.time.format.ISODateTimeFormat
import org.joda.time.DateTime
import com.normation.cfclerk.domain.PolicyPackageName
import com.normation.cfclerk.domain.SectionSpec
import com.normation.cfclerk.services.GitRepositoryProvider
import com.normation.exceptions.TechnicalException
import com.normation.rudder.domain.policies.ConfigurationRule
import com.normation.rudder.domain.policies.ConfigurationRuleId
import com.normation.rudder.domain.policies.PolicyInstance
import com.normation.rudder.domain.policies.PolicyInstanceId
import com.normation.rudder.domain.policies.UserPolicyTemplate
import com.normation.rudder.domain.policies.UserPolicyTemplateCategory
import com.normation.rudder.domain.policies.UserPolicyTemplateCategoryId
import com.normation.rudder.domain.policies.UserPolicyTemplateId
import com.normation.rudder.repository.ArchiveId
import com.normation.rudder.repository.GitConfigurationRuleArchiver
import com.normation.rudder.repository.GitPolicyInstanceArchiver
import com.normation.rudder.repository.GitUserPolicyTemplateArchiver
import com.normation.rudder.repository.GitUserPolicyTemplateCategoryArchiver
import com.normation.rudder.services.marshalling.ConfigurationRuleSerialisation
import com.normation.rudder.services.marshalling.PolicyInstanceSerialisation
import com.normation.rudder.services.marshalling.UserPolicyTemplateCategorySerialisation
import com.normation.rudder.services.marshalling.UserPolicyTemplateSerialisation
import com.normation.utils.Utils
import com.normation.utils.Control.sequence
import net.liftweb.common._
import net.liftweb.util.Helpers.tryo
import com.normation.cfclerk.domain.PolicyPackage
import com.normation.cfclerk.services.PolicyPackageService
import com.normation.rudder.repository.PolicyInstanceRepository
import scala.collection.mutable.Buffer
import scala.collection.JavaConversions._

/**
 * Utility trait that factor out file commits. 
 */
trait GitCommitModification extends Loggable {
  
  def gitRepo : GitRepositoryProvider
  def gitRootDirectory : File
  def relativePath : String
  
  def newArchiveId = ArchiveId((DateTime.now()).toString(ISODateTimeFormat.dateTime))
  
  lazy val getRootDirectory : File = { 
    val file = new File(gitRootDirectory, relativePath)
    Utils.createDirectory(file) match {
      case Full(dir) => dir
      case eb:EmptyBox =>
        val e = eb ?~! "Error when checking required directories '%s' to archive in git:".format(file.getPath)
        logger.error(e.messageChain)
        throw new TechnicalException(e.messageChain)
    } 
  }
  
  private[this] final lazy val git = new Git(gitRepo.db)
  
  /**
   * Files in gitPath are added. 
   * commitMessage is used for the message of the commit. 
   */
  def commitAddFile(gitPath:String, commitMessage:String) = synchronized {
    tryo {
      git.add.addFilepattern(gitPath).call
      val status = git.status.call
      if(status.getAdded.contains(gitPath)||status.getChanged.contains(gitPath)) {
        git.commit.setMessage(commitMessage).call
        newArchiveId
      } else throw new Exception("Auto-archive git failure: not found in git added files: " + gitPath)
    }
  }
  
  /**
   * Files in gitPath are removed. 
   * commitMessage is used for the message of the commit. 
   */
  def commitRmFile(gitPath:String, commitMessage:String) = synchronized {
    tryo {
      git.rm.addFilepattern(gitPath).call
      val status = git.status.call
      if(status.getRemoved.contains(gitPath)) {
        git.commit.setMessage(commitMessage).call
        newArchiveId
      } else throw new Exception("Auto-archive git failure: not found in git removed files: " + gitPath)
    }
  }
  
  /**
   * Commit files in oldGitPath and newGitPath, trying to commit them so that
   * git is aware of moved from old files to new ones. 
   * More preciselly, files in oldGitPath are 'git rm', files in newGitPath are
   * 'git added' (with and without the 'update' mode). 
   * commitMessage is used for the message of the commit. 
   */
  def commitMvDirectory(oldGitPath:String, newGitPath:String, commitMessage:String) = synchronized {
    tryo {
      git.rm.addFilepattern(oldGitPath).call
      git.add.addFilepattern(newGitPath).call
      git.add.setUpdate(true).addFilepattern(newGitPath).call //if some files were removed from dest dir
      val status = git.status.call
      if(status.getAdded.exists( path => path.startsWith(newGitPath) ) ) {
        git.commit.setMessage(commitMessage).call
        newArchiveId
      } else throw new Exception("Auto-archive git failure when moving directory (not found in added file): " + newGitPath)
    }
  }
  
  /**
   * Commit all the modifications for files under the given path.
   * The commitMessage is used in the commit. 
   */
  def commitFullGitPathContent(gitPath:String, commitMessage:String) : Box[String] = synchronized {
    tryo {
      //remove existing and add modified
      git.add.setUpdate(true).addFilepattern(gitPath).call
      //also add new one
      git.add.addFilepattern(gitPath).call
      git.commit.setMessage(commitMessage).call.name
    }
  }
  
  def toGitPath(fsPath:File) = fsPath.getPath.replace(gitRootDirectory.getPath +"/","")
}

class GitConfigurationRuleArchiverImpl(
    override val gitRepo            : GitRepositoryProvider
  , override val gitRootDirectory   : File
  , configurationRuleSerialisation  : ConfigurationRuleSerialisation
  , configurationRuleRootDir        : String //relative path !
  , xmlPrettyPrinter                : PrettyPrinter
  , encoding                        : String = "UTF-8"
) extends GitConfigurationRuleArchiver with Loggable with GitCommitModification {

  override lazy val relativePath = configurationRuleRootDir

  private[this] def newCrFile(crId:ConfigurationRuleId) = new File(getRootDirectory, crId.value + ".xml")
  
  def archiveConfigurationRule(cr:ConfigurationRule, gitCommitCr:Boolean = true) : Box[File] = {
    val crFile = newCrFile(cr.id)
      
    for {   
      archive <- tryo { 
                   FileUtils.writeStringToFile(
                       crFile
                     , xmlPrettyPrinter.format(configurationRuleSerialisation.serialise(cr))
                     , encoding
                   )
                   logger.debug("Archived Configuration rule: " + crFile.getPath)
                   crFile
                 }
      commit  <- if(gitCommitCr) {
                    commitAddFile(toGitPath(newCrFile(cr.id)), "Archive configuration rule with ID '%s'".format(cr.id.value))
                 } else {
                   Full("ok")
                 }
    } yield {
      archive
    }
  }

  def commitConfigurationRules() : Box[String] = {
    this.commitFullGitPathContent(
        configurationRuleRootDir
      , "Commit all modification done on configuration rules (git path: '%s')".format(configurationRuleRootDir)
    )
  }
  
  def deleteConfigurationRule(crId:ConfigurationRuleId, gitCommitCr:Boolean = true) : Box[File] = {
    val crFile = newCrFile(crId)
    if(crFile.exists) {
      for {
        deleted  <- tryo { 
                      FileUtils.forceDelete(crFile) 
                      logger.debug("Deleted archive of configuration rule: " + crFile.getPath)
                    }
        commited <- if(gitCommitCr) {
                      commitRmFile(toGitPath(newCrFile(crId)), "Delete archive of configuration rule with ID '%s' on %s ".format(crId.value))
                    } else {
                      Full("OK")
                    }
      } yield {
        crFile
      }
    } else {
      Full(crFile)
    }
  }
  
}


/**
 * An Utility trait that allows to build the path from a root directory
 * to the category directory from a list of category ids.
 * Basically, it builds the list of directory has path, special casing
 * the root directory to be the given root file. 
 */
trait BuildCategoryPathName {
  //obtain the root directory from the main class mixed with me
  def getRootDirectory : File
  
  //list of directories : don't forget the one for the serialized category. 
  //revert the order to start by the root of policy library. 
  def newCategoryDirectory(uptcId:UserPolicyTemplateCategoryId, parents: List[UserPolicyTemplateCategoryId]) : File = {
    parents match {
      case Nil => //that's the root
        getRootDirectory
      case h::tail => //skip the head, which is the root category
        new File(newCategoryDirectory(h, tail), uptcId.value)
    }
  }  
}

/**
 * A specific trait to create archive of an user policy template category.
 * 
 * Basically, we directly map the category tree to file-system directories,
 * with the root category being the file denoted by "policyLibraryRootDir"
 * 
 */
class GitUserPolicyTemplateCategoryArchiverImpl(
    override val gitRepo                   : GitRepositoryProvider
  , override val gitRootDirectory          : File
  , userPolicyTemplateCategorySerialisation: UserPolicyTemplateCategorySerialisation
  , policyLibraryRootDir                   : String //relative path !
  , xmlPrettyPrinter                       : PrettyPrinter
  , encoding                               : String = "UTF-8"
) extends GitUserPolicyTemplateCategoryArchiver with Loggable with GitCommitModification with BuildCategoryPathName {

  override lazy val relativePath = policyLibraryRootDir
  
  private[this] def newUptcFile(uptcId:UserPolicyTemplateCategoryId, parents: List[UserPolicyTemplateCategoryId]) = {
    new File(newCategoryDirectory(uptcId, parents), "category.xml") 
  }
  
  private[this] def archiveWithRename(uptc:UserPolicyTemplateCategory, oldParents: Option[List[UserPolicyTemplateCategoryId]], newParents: List[UserPolicyTemplateCategoryId], gitCommit:Boolean = true) : Box[File] = {     
    val uptcFile = newUptcFile(uptc.id, newParents)
    
    for {
      archive     <- tryo { 
                       FileUtils.writeStringToFile(
                           uptcFile
                         , xmlPrettyPrinter.format(userPolicyTemplateCategorySerialisation.serialise(uptc))
                         , encoding
                       )
                       logger.debug("Archived policy library category: " + uptcFile.getPath)
                       uptcFile
                     }
      uptcGitPath =  toGitPath(newUptcFile(uptc.id, newParents))
      commit      <- if(gitCommit) {
                      oldParents match {
                        case Some(olds) => 
                          val oldPath = toGitPath(newUptcFile(uptc.id, olds))
                          commitMvDirectory(oldPath, uptcGitPath, "Move archive of policy library category with ID '%s'".format(uptc.id.value))
                        case None       => 
                          commitAddFile(uptcGitPath, "Archive of policy library category with ID '%s'".format(uptc.id.value))
                      }
                    } else {
                      Full("ok")
                    }
    } yield {
      archive
    }
  }

  def archiveUserPolicyTemplateCategory(uptc:UserPolicyTemplateCategory, getParents: List[UserPolicyTemplateCategoryId], gitCommit:Boolean = true) : Box[File] = {     
    archiveWithRename(uptc, None, getParents, gitCommit)
  }
  
  def deleteUserPolicyTemplateCategory(uptcId:UserPolicyTemplateCategoryId, getParents: List[UserPolicyTemplateCategoryId], gitCommit:Boolean = true) : Box[File] = {
    val uptcFile = newUptcFile(uptcId, getParents)
    if(uptcFile.exists) {
      for {
        //don't forget to delete the category *directory*
        deleted  <- tryo { 
                      FileUtils.forceDelete(uptcFile.getParentFile) 
                      logger.debug("Deleted archived policy library category: " + uptcFile.getPath)
                    }
        path     =  toGitPath(newUptcFile(uptcId, getParents))
        commited <- if(gitCommit) {
                      commitRmFile(path, "Delete archive of policy library category with ID '%s'".format(uptcId.value))
                    } else {
                      Full("OK")
                    }
      } yield {
        uptcFile
      }
    } else {
      Full(uptcFile)
    }
  }
  // TODO : keep content when moving !!!
  // well, for now, that's ok, because we can only move empty categories
  def moveUserPolicyTemplateCategory(uptc:UserPolicyTemplateCategory, oldParents: List[UserPolicyTemplateCategoryId], newParents: List[UserPolicyTemplateCategoryId], gitCommit:Boolean = true) : Box[File] = {
    for {
      deleted  <- deleteUserPolicyTemplateCategory(uptc.id, oldParents, false)
      archived <- archiveWithRename(uptc, Some(oldParents), newParents, gitCommit)
    } yield {
      archived
    }
  }
  
  /**
   * Commit modification done in the Git repository for any
   * category, policy template and policy instance in the
   * user policy library.
   * Return the git commit id. 
   */
  def commitUserPolicyLibrary : Box[String] = {
    this.commitFullGitPathContent(
        policyLibraryRootDir
      , "Commit all modification done in the User Policy Library (git path: '%s')".format(policyLibraryRootDir)
    )
  }
}


trait UptModificationCallback {
  
  //Name of the callback, for debugging
  def uptModificationCallbackName : String

  /**
   * What to do on upt save
   */
  def onArchive(upt:UserPolicyTemplate, parents: List[UserPolicyTemplateCategoryId], gitCommit:Boolean = true) : Box[Unit]

  /**
   * What to do on upt deletion
   */
  def onDelete(ptName:PolicyPackageName, getParents: List[UserPolicyTemplateCategoryId], gitCommit:Boolean = true) : Box[Unit]

  /**
   * What to do on upt move
   */
  def onMove(upt:UserPolicyTemplate, oldParents: List[UserPolicyTemplateCategoryId], newParents: List[UserPolicyTemplateCategoryId], gitCommit:Boolean = true) : Box[Unit]
}

class UpdatePiOnUptEvent(
    gitPiArchiver: GitPolicyInstanceArchiver
  , ptRepository : PolicyPackageService
  , piRepository : PolicyInstanceRepository    
) extends UptModificationCallback with Loggable {
  override val uptModificationCallbackName = "Update PI on UPT events"
  
  def onArchive(upt:UserPolicyTemplate, parents: List[UserPolicyTemplateCategoryId], gitCommit:Boolean = true) : Box[Unit] = {
    
    logger.debug("Executing archivage of PIs for UPT '%s'".format(upt))
    
    if(upt.policyInstances.isEmpty) Full("OK")
    else {
      for {
        pt  <- Box(ptRepository.getLastPolicyByName(upt.referencePolicyTemplateName))
        pis <- sequence(upt.policyInstances) { piId =>
                 for {
                   pi         <- piRepository.getPolicyInstance(piId)
                   archivedPi <- gitPiArchiver.archivePolicyInstance(pi, pt.id.name, parents, pt.rootSection, gitCommit = false)
                 } yield {
                   archivedPi
                 }
               }
      } yield {
        pis
      }
    }
  }
  
  override def onDelete(ptName:PolicyPackageName, getParents: List[UserPolicyTemplateCategoryId], gitCommit:Boolean = true) = Full({})
  override def onMove(upt:UserPolicyTemplate, oldParents: List[UserPolicyTemplateCategoryId], newParents: List[UserPolicyTemplateCategoryId], gitCommit:Boolean = true) = Full({})
}

/**
 * A specific trait to create archive of an user policy template.
 */
class GitUserPolicyTemplateArchiverImpl(
    override val gitRepo           : GitRepositoryProvider
  , override val gitRootDirectory  : File
  , userPolicyTemplateSerialisation: UserPolicyTemplateSerialisation
  , policyLibraryRootDir           : String //relative path !
  , xmlPrettyPrinter               : PrettyPrinter
  , encoding                       : String = "UTF-8"
  , val uptModificationCallback    : Buffer[UptModificationCallback] = Buffer()
  , val userPolicyTemplateFileName : String = "userPolicyTemplateSettings.xml"
) extends GitUserPolicyTemplateArchiver with Loggable with GitCommitModification with BuildCategoryPathName {

  override lazy val relativePath = policyLibraryRootDir
  private[this] def newUptFile(ptName:PolicyPackageName, parents: List[UserPolicyTemplateCategoryId]) = {
    //parents can not be null: we must have at least the root category
    parents match {
      case Nil => Failure("UPT '%s' was asked to be saved in a category which does not exists (empty list of parents, not even the root cateogy was given!)".format(ptName.value))
      case h::tail => Full(new File(new File(newCategoryDirectory(h,tail),ptName.value), userPolicyTemplateFileName))
    }
  }
  
  def archiveUserPolicyTemplate(upt:UserPolicyTemplate, parents: List[UserPolicyTemplateCategoryId], gitCommit:Boolean = true) : Box[File] = {     
    for {
      uptFile   <- newUptFile(upt.referencePolicyTemplateName, parents)
      archive   <- tryo { 
                     FileUtils.writeStringToFile(
                         uptFile
                       , xmlPrettyPrinter.format(userPolicyTemplateSerialisation.serialise(upt))
                       , encoding
                     )
                     logger.debug("Archived policy library template: " + uptFile.getPath)
                     uptFile
                   }
      callbacks <- sequence(uptModificationCallback) { _.onArchive(upt,parents, false) }
      commit    <- if(gitCommit) {
                     commitAddFile(toGitPath(uptFile), "Archive of policy library template for policy template name '%s'".format(upt.referencePolicyTemplateName.value))
                   } else {
                     Full("ok")
                   }
    } yield {
      archive
    }
  }
  
  def deleteUserPolicyTemplate(ptName:PolicyPackageName, parents: List[UserPolicyTemplateCategoryId], gitCommit:Boolean = true) : Box[File] = {
    newUptFile(ptName, parents) match {
      case Full(uptFile) if(uptFile.exists) =>
        for {
          //don't forget to delete the category *directory*
          deleted  <- tryo { 
                        if(uptFile.exists) FileUtils.forceDelete(uptFile) 
                        logger.debug("Deleted archived policy library template: " + uptFile.getPath)
                      }
          path     =  toGitPath(uptFile)
          callbacks <- sequence(uptModificationCallback) { _.onDelete(ptName, parents, false) }
          commited <- if(gitCommit) {
                        commitRmFile(path, "Delete archive of policy library template for policy template name '%s'".format(ptName.value))
                      } else {
                        Full("OK")
                      }
        } yield {
          uptFile
        }
      case other => other
    }
  }
 
  /*
   * For that one, we have to move the directory of the User policy templates 
   * to its new parent location. 
   * If the commit has to be done, we have to add all files under that new repository,
   * and remove from old one.
   * 
   * As we can't know at all if all PI currently defined for an UPT were saved, we
   * DO have to always consider a fresh new archive. 
   */
  def moveUserPolicyTemplate(upt:UserPolicyTemplate, oldParents: List[UserPolicyTemplateCategoryId], newParents: List[UserPolicyTemplateCategoryId], gitCommit:Boolean = true) : Box[File] = {
    for {
      oldUptFile      <- newUptFile(upt.referencePolicyTemplateName, oldParents)
      oldUptDirectory =  oldUptFile.getParentFile
      newUptFile      <- newUptFile(upt.referencePolicyTemplateName, newParents)
      newUptDirectory =  newUptFile.getParentFile
      clearNew        <- tryo {
                           if(newUptDirectory.exists) FileUtils.forceDelete(newUptDirectory)
                           else "ok"
                         }
      deleteOld       <- tryo {
                           if(oldUptDirectory.exists) FileUtils.forceDelete(oldUptDirectory)
                           else "ok"
                         }
      archived        <- archiveUserPolicyTemplate(upt, newParents, false)
      commited        <- if(gitCommit) {
                           commitMvDirectory(
                               toGitPath(oldUptDirectory)
                             , toGitPath(newUptDirectory)
                             , "Move user policy template for policy template name '%s'".format(upt.referencePolicyTemplateName.value)
                           )
                         } else {
                           Full("OK")
                         }
    } yield {
      newUptDirectory
    }
  }
}


/**
 * A specific trait to create archive of an user policy template.
 */
class GitPolicyInstanceArchiverImpl(
    override val gitRepo           : GitRepositoryProvider
  , override val gitRootDirectory  : File
  , policyInstanceSerialisation    : PolicyInstanceSerialisation
  , policyLibraryRootDir           : String //relative path !
  , xmlPrettyPrinter               : PrettyPrinter
  , encoding                       : String = "UTF-8"
) extends GitPolicyInstanceArchiver with Loggable with GitCommitModification with BuildCategoryPathName {

  override lazy val relativePath = policyLibraryRootDir
  
  private[this] def newPiFile(
      piId   : PolicyInstanceId
    , ptName : PolicyPackageName
    , parents: List[UserPolicyTemplateCategoryId]
  ) = {
    parents match {
      case Nil => Failure("Can not save policy instance '%s' for policy template '%s' because no category (not even the root one) was given as parent for that policy template".format(piId.value, ptName.value))
      case h::tail => 
        Full(new File(new File(newCategoryDirectory(h, tail), ptName.value), piId.value+".xml"))
    }
  }
  
  def archivePolicyInstance(
      pi                 : PolicyInstance
    , ptName             : PolicyPackageName
    , catIds             : List[UserPolicyTemplateCategoryId]
    , variableRootSection: SectionSpec
    , gitCommit          : Boolean = true
  ) : Box[File] = {
        
    for {
      piFile  <- newPiFile(pi.id, ptName, catIds)
      archive <- tryo { 
                   FileUtils.writeStringToFile(
                       piFile
                     , xmlPrettyPrinter.format(policyInstanceSerialisation.serialise(ptName, variableRootSection, pi))
                     , encoding
                   )
                   logger.debug("Archived policy instance: " + piFile.getPath)
                   piFile
                 }
      commit  <- if(gitCommit) {
                    commitAddFile(toGitPath(piFile), "Archive policy instance with ID '%s'".format(pi.id.value))
                 } else {
                   Full("ok")
                 }
    } yield {
      archive
    }    
  }
    
  /**
   * Delete an archived policy instance. 
   * If gitCommit is true, the modification is
   * saved in git. Else, no modification in git are saved.
   */
  def deletePolicyInstance(
      piId:PolicyInstanceId
    , ptName   : PolicyPackageName
    , catIds   : List[UserPolicyTemplateCategoryId]
    , gitCommit: Boolean = true
  ) : Box[File] = {
    newPiFile(piId, ptName, catIds) match {
      case Full(piFile) if(piFile.exists) =>
        for {
          deleted  <- tryo { 
                        FileUtils.forceDelete(piFile) 
                        logger.debug("Deleted archive of policy instance: " + piFile.getPath)
                      }
          commited <- if(gitCommit) {
                        commitRmFile(toGitPath(piFile), "Delete archive of policy instance with ID '%s' on %s ".format(piId.value))
                      } else {
                        Full("OK")
                      }
        } yield {
          piFile
        }
      case other => other
    }
  }
}