/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.loadBalancer

import java.util.concurrent.atomic.AtomicInteger

import scala.collection.concurrent.TrieMap
import scala.concurrent.Promise
import whisk.core.entity.{ActivationId, UUID, WhiskActivation}
import whisk.core.entity.InstanceId

import scala.reflect.runtime.universe._
import scala.collection.mutable

/** Encapsulates data relevant for a single activation */
case class ActivationEntry(id: ActivationId, namespaceId: UUID, invokerName: InstanceId, promise: Promise[Either[ActivationId, WhiskActivation]])

/**
 * Encapsulates data used for loadbalancer and active-ack bookkeeping.
 *
 * Note: The state keeping is backed by concurrent data-structures. As such,
 * concurrent reads can return stale values (especially the counters returned).
 */
class LoadBalancerData() {

    private val activationByInvoker = TrieMap[InstanceId, AtomicInteger]()
    private val activationByNamespaceId = TrieMap[UUID, AtomicInteger]()
    private val activationsById = TrieMap[ActivationId, mutable.Seq[ActivationEntry]]()
    private val totalActivations = new AtomicInteger(0)

    /** Get the number of activations across all namespaces. */
    def totalActivationCount = totalActivations.get

    /**
     * Get the number of activations for a specific namespace.
     *
     * @param namespace The namespace to get the activation count for
     * @return a map (namespace -> number of activations in the system)
     */
    def activationCountOn(namespace: UUID) = {
        activationByNamespaceId.get(namespace).map(_.get).getOrElse(0)
    }

    /**
     * Get the number of activations for a specific invoker.
     *
     * @param invoker The invoker to get the activation count for
     * @return a map (invoker -> number of activations queued for the invoker)
     */
    def activationCountOn(invoker: InstanceId): Int = {
        activationByInvoker.get(invoker).map(_.get).getOrElse(0)
    }

    /**
     * Get an activation entry for a given activation id.
     *
     * @param activationId activation id to get data for
     * @return the respective activation or None if it doesn't exist
     */
    def activationById(activationId: ActivationId): Option[ActivationEntry] = {
        activationsById.get(activationId).get.headOption //TODO: Bad temporary patch
    }

    def matchListToAdd[A: TypeTag](list: mutable.Seq[A], entry: ActivationEntry) = list match {
        case activationEntryList: mutable.Seq[ActivationEntry @unchecked] if typeOf[A] =:= typeOf[ActivationEntry] =>
            activationEntryList :+ entry
    }

    /**
     * Adds an activation entry.
     *
     * @param id identifier to deduplicate the entry
     * @param update block calculating the entry to add.
     *               Note: This is evaluated iff the entry
     *               didn't exist before.
     * @return the entry calculated by the block or iff it did
     *         exist before the entry from the state
     */
    def putActivation(id: ActivationId, update: => ActivationEntry): ActivationEntry = {
        //Activations of the same action only count as one
        val entry = update
        activationsById.get(id) match {
            case Some(entryList) => //If there is already an activation id
                matchListToAdd(entryList, entry) //Add another activationEntry to the entryList

            case None => //There's no activation id yet
                totalActivations.incrementAndGet()
                activationByNamespaceId.getOrElseUpdate(entry.namespaceId, new AtomicInteger(0)).incrementAndGet()
        }

        activationByInvoker.getOrElseUpdate(entry.invokerName, new AtomicInteger(0)).incrementAndGet()
        entry
    }

/*    def removeActivation(entry: ActivationEntry): Option[ActivationEntry] = {
        //Se tiver uma activation entry ainda, faz remove
        //Se não, atualiza apenas a entry
        var res : Option[ActivationEntry] = Option(entry)
        if (activationsById.contains(entry.id)) {
            if (activationsById.get(entry.id).get.size > 1) {
                val entryList = activationsById.get(entry.id)
                val newList = entryList.filter { !_.equals(entry) }.get
                activationsById.update(entry.id, newList)
                if(entryList.size == newList.size - 1)
                    res = Option(entry)
                else
                    res = None
            } else {
                activationsById.remove(entry.id).map { x =>
                  totalActivations.decrementAndGet()
                  activationByNamespaceId.getOrElseUpdate(entry.namespaceId, new AtomicInteger(0)).decrementAndGet()
                  activationByInvoker.getOrElseUpdate(entry.invokerName, new AtomicInteger(0)).decrementAndGet()
                  res = x.headOption
                }
            }
        }
        res
    }
*/
    def removeActivation(entry: ActivationEntry): Unit = {
        activationsById.remove(entry.id).foreach { x =>
            totalActivations.decrementAndGet()
            activationByNamespaceId.getOrElseUpdate(entry.namespaceId, new AtomicInteger(0)).decrementAndGet()
            x.foreach { activation =>
                activationByInvoker.getOrElseUpdate(activation.invokerName, new AtomicInteger(0)).decrementAndGet()
            }
        }
    }

    /*
    /**
     * Removes the given entry.
     *
     * @param entry the entry to remove
     * @return The deleted entry or None if nothing got deleted
     */
    def removeActivation(entry: ActivationEntry): Option[ActivationEntry] = {
        activationsById.remove(entry.id).map { x =>
            totalActivations.decrementAndGet()
            activationByNamespaceId.getOrElseUpdate(entry.namespaceId, new AtomicInteger(0)).decrementAndGet()
            activationByInvoker.getOrElseUpdate(entry.invokerName, new AtomicInteger(0)).decrementAndGet()
            x
        }
    }
*/
    def matchListToRemove[A: TypeTag](list: mutable.Seq[A]) = list match {
        case activationEntryList: mutable.Seq[ActivationEntry @unchecked] if typeOf[A] =:= typeOf[ActivationEntry] =>
            removeActivation(activationEntryList.head.asInstanceOf[ActivationEntry])
    }

    /**
     * Removes the activation identified by the given activation id.
     *
     * @param aid activation id to remove
     * @return The deleted entry or None if nothing got deleted
     */
    def removeActivation(aid: ActivationId): Option[Seq[ActivationEntry]] = {
        val list = activationsById.get(aid)
        list match {
            case Some(matchedList) =>
                matchListToRemove(matchedList)
                list
            case None =>
                None
        }
    }
}
