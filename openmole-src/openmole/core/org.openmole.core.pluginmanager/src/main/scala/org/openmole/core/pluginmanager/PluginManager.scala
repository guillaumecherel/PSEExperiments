/*
 * Copyright (C) 2011 Romain Reuillon
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.openmole.core.pluginmanager

import java.io.File
import java.io.FileFilter
import java.io.FileInputStream
import org.openmole.core.exception.{ InternalProcessingError, UserBadDataError }
import org.openmole.core.pluginmanager.internal.Activator
import org.openmole.core.tools.io.FileUtil
import org.openmole.core.tools.service.Logger
import FileUtil._
import org.osgi.framework.Bundle
import scala.collection.mutable.HashMap
import scala.collection.mutable.HashSet
import scala.collection.mutable.ListBuffer
import org.osgi.framework.BundleEvent
import org.osgi.framework.BundleListener
import scala.collection.JavaConversions._
import util.Try

import scala.util.{ Failure, Success, Try }

object PluginManager extends Logger {

  import Log._

  private var files = Map.empty[File, (Long, Long)]
  private var resolvedDirectDependencies = HashMap.empty[Long, HashSet[Long]]
  private var resolvedPluginDependenciesCache = HashMap.empty[Long, Iterable[Long]]
  private var providedDependencies = Set.empty[Long]

  updateDependencies

  Activator.contextOrException.addBundleListener(new BundleListener {
    override def bundleChanged(event: BundleEvent) = {
      val b = event.getBundle
      if (event.getType == BundleEvent.RESOLVED || event.getType == BundleEvent.UNRESOLVED || event.getType == BundleEvent.UPDATED) updateDependencies
    }
  })

  def bundles = Activator.contextOrException.getBundles.filter(!_.isSystem).toSeq
  def bundleFiles = files.keys
  def dependencies(file: File): Option[Iterable[File]] =
    files.get(file).map { case (id, _) ⇒ allPluginDependencies(id).map { l ⇒ Activator.contextOrException.getBundle(l).file } }

  def isClassProvidedByAPlugin(c: Class[_]) = {
    val b = Activator.packageAdmin.getBundle(c)
    if (b != null) !providedDependencies.contains(b.getBundleId)
    else false
  }

  def fileProviding(c: Class[_]) =
    Option(Activator.packageAdmin.getBundle(c)).map(b ⇒ Activator.contextOrException.getBundle(b.getBundleId).file.getCanonicalFile)

  def bundleForClass(c: Class[_]): Bundle = Activator.packageAdmin.getBundle(c)

  def bundlesForClass(c: Class[_]): Iterable[Bundle] = synchronized {
    allDependencies(bundleForClass(c).getBundleId).map { Activator.contextOrException.getBundle }
  }

  def pluginsForClass(c: Class[_]): Iterable[File] = synchronized {
    allPluginDependencies(bundleForClass(c).getBundleId).map { l ⇒ Activator.contextOrException.getBundle(l).file }
  }

  def unload(file: File) = synchronized {
    bundle(file) match {
      case Some(b) ⇒ b.uninstall
      case None    ⇒
    }
  }

  def allDepending(file: File): Iterable[File] = synchronized {
    bundle(file) match {
      case Some(b) ⇒ allDependingBundles(b).map { _.file }
      case None    ⇒ Iterable.empty
    }
  }

  def plugins(path: File): Iterable[File] = {
    def isDirectoryPlugin(file: File) = file.isDirectory && file.child("META-INF").child("MANIFEST.MF").exists

    if (isDirectoryPlugin(path) || path.isJar) List(path)
    else
      path.listFiles(new FileFilter {
        override def accept(file: File): Boolean =
          (file.isFile && file.exists && file.isJar) ||
            isDirectoryPlugin(file)
      })
  }

  def tryLoad(files: Iterable[File]) = synchronized {
    val bundles =
      files.flatMap { plugins }.flatMap {
        b ⇒
          Try(installBundle(b)) match {
            case Success(r) ⇒ Some(r)
            case Failure(e) ⇒
              logger.log(WARNING, s"Error installing bundle $b", e)
              None
          }
      }.toList
    bundles.foreach {
      b ⇒
        logger.fine(s"Stating bundle ${b.getLocation}")
        b.start
    }
  }

  def load(files: Iterable[File]) = synchronized {
    val bundles = files.flatMap { plugins }.map { installBundle }.toList
    bundles.foreach {
      b ⇒
        logger.fine(s"Stating bundle ${b.getLocation}")
        b.start
    }
    bundles
  }

  def loadIfNotAlreadyLoaded(plugins: Iterable[File]) = synchronized {
    val bundles = plugins.filterNot(f ⇒ files.contains(f)).map(installBundle).toList
    bundles.foreach { _.start }
  }

  def load(path: File): Unit = load(List(path))

  def loadDir(path: File): Unit =
    if (path.exists && path.isDirectory) load(plugins(path))

  def bundle(file: File) = files.get(file.getCanonicalFile).map { id ⇒ Activator.contextOrException.getBundle(id._1) }

  private def dependencies(bundles: Iterable[Long]): Iterable[Long] = synchronized {
    val ret = new ListBuffer[Long]
    var toProceed = new ListBuffer[Long] ++ bundles

    while (!toProceed.isEmpty) {
      val cur = toProceed.remove(0)
      ret += cur
      toProceed ++= resolvedDirectDependencies.getOrElse(cur, Iterable.empty).filter(b ⇒ !ret.contains(b))
    }

    ret.distinct
  }

  private def allDependencies(b: Long) = synchronized { dependencies(List(b)) }

  private def allPluginDependencies(b: Long) = synchronized {
    resolvedPluginDependenciesCache.getOrElseUpdate(b, dependencies(List(b)).filter(b ⇒ !providedDependencies.contains(b)))
  }

  private def installBundle(f: File) = try {
    logger.fine(s"Install bundle $f")

    if (!f.exists) throw new UserBadDataError("Bundle file " + f + " doesn't exists.")
    val file = f.getCanonicalFile

    files.get(file) match {
      case None ⇒
        val ret = Activator.contextOrException.installBundle(file.toURI.toString)
        files += file -> ((ret.getBundleId, file.lastModification))
        ret
      case Some(bundleId) ⇒
        val bundle = Activator.contextOrException.getBundle(bundleId._1)
        //FileService.invalidate(bundle, file)
        if (file.lastModification != bundleId._2) {
          val is = new FileInputStream(f)
          try bundle.update(is)
          finally is.close
        }
        bundle
    }
  }
  catch {
    case t: Throwable ⇒ throw new InternalProcessingError(t, "Installing bundle " + f)
  }

  def startAll = Activator.contextOrException.getBundles.foreach(_.start)

  private def updateDependencies = synchronized {
    resolvedDirectDependencies = new HashMap[Long, HashSet[Long]]
    bundles.foreach {
      b ⇒
        dependingBundles(b).foreach {
          db ⇒ resolvedDirectDependencies.getOrElseUpdate(db.getBundleId, new HashSet[Long]) += b.getBundleId
        }
    }

    resolvedPluginDependenciesCache = new HashMap[Long, Iterable[Long]]
    providedDependencies = dependencies(bundles.filter(b ⇒ b.isProvided).map { _.getBundleId }).toSet
    files = bundles.map(b ⇒ b.file.getCanonicalFile -> ((b.getBundleId, b.file.lastModification))).toMap
  }

  private def allDependingBundles(b: Bundle): Iterable[Bundle] =
    b :: dependingBundles(b).flatMap(allDependingBundles).toList

  private def dependingBundles(b: Bundle): Iterable[Bundle] = {
    val exportedPackages = Activator.packageAdmin.getExportedPackages(b)

    if (exportedPackages != null) {
      for (exportedPackage ← exportedPackages; ib ← exportedPackage.getImportingBundles) yield ib
    }
    else Iterable.empty
  }

}
