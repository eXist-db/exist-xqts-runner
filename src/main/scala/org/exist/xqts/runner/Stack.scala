/*
 * Copyright (C) 2018  The eXist Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.exist.xqts.runner

import scala.collection.immutable.Nil

/**
  * A very simple Stack implementation
  * build atop a {@link List}.
  *
  * @param list the list which backs the stack
  *
  * @author Adam Retter <adam@evolvedbinary.com>
  */
class Stack[+A](list: List[A]) {

  /**
    * Constructor for an empty stack.
    */
  def this() = this(Nil)

  /**
    * Places a new item onto the top of the stack.
    *
    * @param x the new item to place atop the stack.
    *
    * @return the new stack.
    */
  def push[B >: A](x: B) : Stack[B] = Stack(x :: list)

  /**
    * Get a copy of the item at the top of the stack.
    *
    * @return the item at the top of the stack.
    *
    * @throws NoSuchElementException if the stack is empty
    */
  @throws[NoSuchElementException]
  def peek : A = list.head

  /**
    * Get a copy of the item at the top of the stack.
    *
    * @return a Some of the item at the top of the stack,
    *         or a None if the stack is empty
    */
  def peekOption: Option[A] = list.headOption

  /**
    * Takes item from the top of the stack.
    *
    * @return A tuple consisting of: the item from the
    *         top of the stack, and the new stack
    *
    * @throws NoSuchElementException if the stack is empty
    */
  @throws[NoSuchElementException]
  def pop() : (A, Stack[A]) = (list.head, Stack(list.tail))

  /**
    * Replaces the item at the top of the stack with a new item.
    *
    * @param x the new item for the head
    * @return the new stack
    */
  def replace[B >: A](x: B) : Stack[B] = Stack(x :: list.tail)

  /**
    * Removes the top item from the stack, and returns the stack.
    *
    * @return the new stack
    */
  def drop() : Stack[A] = Stack(list.tail)

  /**
    * Gets the depth of the stack.
    *
    * @return the depth of the stack
    */
  def size: Int = list.size
}

object Stack {
  def empty[A] : Stack[A] = new Stack()
  def apply[A] (head: A) = new Stack(List(head))
  def apply[A] (list: List[A]) = new Stack(list)
}