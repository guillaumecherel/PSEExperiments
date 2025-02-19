/*
 * Copyright (C) 2010 Romain Reuillon
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

package org.openmole.core.workflow.data

import org.openmole.core.exception.InternalProcessingError
import org.openmole.core.workflow.mole._
import org.openmole.core.workflow.task._
import org.openmole.core.workflow.transition._
import org.openmole.core.workflow.tools._

import scala.collection.mutable.ListBuffer

object DataChannel {
  def levelDelta(mole: Mole)(dataChannel: DataChannel): Int =
    mole.level(dataChannel.end.capsule) - mole.level(dataChannel.start)

  def apply(start: Capsule, end: Slot, filter: Filter[String]) = new DataChannel(start, end, filter)
}

/**
 * A data channel allow to transmit data between remotes task capsules within a mole.
 * Two capsules could be linked with a {@link DataChannel} if:
 *      - they belong to the same mole,
 *      - there is no capsule with more than one input slot in a path between
 *        the two capsules.
 *
 * @param start the capsule from which the data channel starts
 * @param end the capsule to which the data channel ends
 * @param filter the filter of the variable transported by this data channel
 */
class DataChannel(
    val start: Capsule,
    val end: Slot,
    val filter: Filter[String]) {

  /**
   * Consums the provided variables and construct a context for them.
   *
   * @param ticket the ticket of the current execution
   * @param moleExecution the current mole execution
   * @return the variables which have been transmitted through this data channel
   */
  def consums(ticket: Ticket, moleExecution: MoleExecution): Iterable[Variable[_]] = moleExecution.synchronized {
    val delta = levelDelta(moleExecution.mole)
    val dataChannelRegistry = moleExecution.dataChannelRegistry

    {
      if (delta <= 0) dataChannelRegistry.remove(this, ticket).getOrElse(new ListBuffer[Variable[_]])
      else {
        val workingOnTicket = (0 until delta).foldLeft(ticket) {
          (c, e) ⇒ c.parent.getOrElse(throw new InternalProcessingError("Bug should never get to root."))
        }
        dataChannelRegistry.consult(this, workingOnTicket) getOrElse (new ListBuffer[Variable[_]])
      }
    }.toIterable
  }

  /**
   * Provides the variable for future consuption by the matching execution of
   * the ending task.
   *
   * @param fromContext the context containing the variables
   * @param ticket the ticket of the current execution
   * @param moleExecution the current mole execution
   */
  def provides(fromContext: Context, ticket: Ticket, moleExecution: MoleExecution) = moleExecution.synchronized {
    val delta = levelDelta(moleExecution.mole)
    val dataChannelRegistry = moleExecution.dataChannelRegistry

    if (delta >= 0) {
      val toContext = ListBuffer() ++ fromContext.values.filterNot(v ⇒ filter(v.prototype.name))
      dataChannelRegistry.register(this, ticket, toContext)
    }
    else {
      val workingOnTicket = (delta until 0).foldLeft(ticket) {
        (c, e) ⇒ c.parent.getOrElse(throw new InternalProcessingError("Bug should never get to root."))
      }
      val toContext = dataChannelRegistry.getOrElseUpdate(this, workingOnTicket, new ListBuffer[Variable[_]])
      toContext ++= fromContext.values.filterNot(v ⇒ filter(v.prototype.name))
    }
  }

  /**
   *
   * Get the set of data of that will actually be transmitted as input to the
   * ending task capsule. This is computed by intersecting the set of variable
   * names transported by this data channel and the set of input of the ending
   * task.
   *
   * @return the transmitted data
   */
  def data(mole: Mole, sources: Sources, hooks: Hooks) =
    start.outputs(mole, sources, hooks).filterNot(d ⇒ filter(d.prototype.name))

  def levelDelta(mole: Mole): Int = DataChannel.levelDelta(mole)(this)

  override def toString = "DataChannel from " + start + " to " + end

}
