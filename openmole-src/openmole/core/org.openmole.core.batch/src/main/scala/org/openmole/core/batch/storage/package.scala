/*
 * Copyright (C) 2014 Romain Reuillon
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.openmole.core.batch

import java.io.File

package object storage {
  implicit def simpleToRemoteConverter(s: SimpleStorage) =
    new RemoteStorage {
      val storage = s

      override def child(parent: String, child: String): String = storage.child(parent, child)
      override def uploadGZ(src: File, dest: String): Unit = storage.uploadGZ(src, dest)
      override def download(src: String, dest: File): Unit = storage.download(src, dest)
      override def upload(src: File, dest: String): Unit = storage.upload(src, dest)
      override def downloadGZ(src: String, dest: File): Unit = storage.downloadGZ(src, dest)
    }
}
