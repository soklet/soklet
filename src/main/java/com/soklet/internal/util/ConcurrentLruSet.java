/*
 * Copyright 2022-2024 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet.internal.util;


import javax.annotation.concurrent.ThreadSafe;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A threadsafe LRU {@link Set}.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class ConcurrentLruSet<E> implements Set<E> {
	private final int capacity;
	private final ConcurrentHashMap<E, Node<E>> map;
	private final ConcurrentLinkedDeque<Node<E>> deque;
	private final ReentrantLock evictionLock;

	public ConcurrentLruSet(int capacity) {
		if (capacity <= 0) {
			throw new IllegalArgumentException("Capacity must be greater than zero.");
		}
		this.capacity = capacity;
		this.map = new ConcurrentHashMap<>(capacity);
		this.deque = new ConcurrentLinkedDeque<>();
		this.evictionLock = new ReentrantLock();
	}

	@Override
	public int size() {
		return map.size();
	}

	@Override
	public boolean isEmpty() {
		return map.isEmpty();
	}

	/**
	 * Checks if the set contains the element. If it does, moves the element to the MRU position.
	 */
	@Override
	public boolean contains(Object o) {
		@SuppressWarnings("unchecked")
		E key = (E) o;
		Node<E> node = map.get(key);
		if (node == null) {
			return false;
		}
		// Move to MRU position
		deque.remove(node);
		deque.addFirst(node);
		return true;
	}

	/**
	 * Adds the element to the set. If the element already exists, it is considered a "touch" and the element
	 * is moved to MRU position. If adding a new element exceeds capacity, the LRU element is evicted.
	 */
	@Override
	public boolean add(E e) {
		Node<E> newNode = new Node<>(e);
		Node<E> existing = map.putIfAbsent(e, newNode);

		if (existing == null) {
			// New element
			deque.addFirst(newNode);
			evictIfNeeded();
			return true;
		} else {
			// Element already present, move it to MRU
			deque.remove(existing);
			deque.addFirst(existing);
			return false; // not a new element
		}
	}

	@Override
	public boolean remove(Object o) {
		@SuppressWarnings("unchecked")
		E key = (E) o;
		Node<E> node = map.remove(key);
		if (node == null) {
			return false;
		}
		deque.remove(node);
		return true;
	}

	@Override
	public void clear() {
		map.clear();
		deque.clear();
	}

	@Override
	public boolean containsAll(Collection<?> c) {
		for (Object e : c) {
			if (!contains(e)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Adds all elements of the collection. Each add updates MRU position of the elements.
	 * If capacity is exceeded, eviction is triggered.
	 */
	@Override
	public boolean addAll(Collection<? extends E> c) {
		boolean modified = false;
		for (E e : c) {
			if (add(e)) {
				modified = true;
			}
		}
		return modified;
	}

	@Override
	public boolean removeAll(Collection<?> c) {
		boolean modified = false;
		for (Object e : c) {
			if (remove(e)) {
				modified = true;
			}
		}
		return modified;
	}

	@Override
	public boolean retainAll(Collection<?> c) {
		// We'll remove any element not in c
		HashSet<?> set = new HashSet<>(c);
		boolean modified = false;
		for (E key : map.keySet()) {
			if (!set.contains(key)) {
				if (remove(key)) {
					modified = true;
				}
			}
		}
		return modified;
	}

	@Override
	public Object[] toArray() {
		return snapshot().toArray();
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> T[] toArray(T[] a) {
		return snapshot().toArray(a);
	}

	@Override
	public Iterator<E> iterator() {
		// Returns a snapshot iterator over the current elements
		return Collections.unmodifiableCollection(snapshot()).iterator();
	}

	/**
	 * Create a snapshot of the keys in the set. This snapshot is not "live".
	 * Changes made to the set after this call won't reflect in the snapshot.
	 */
	private Collection<E> snapshot() {
		// snapshot from map keys (or deque)
		// map keys are sufficient and O(n), stable under concurrency
		return new ArrayList<>(map.keySet());
	}

	/**
	 * Evict the least recently used elements if we exceed capacity.
	 * Only locked if needed, minimizing contention.
	 */
	private void evictIfNeeded() {
		if (map.size() > capacity) {
			evictionLock.lock();
			try {
				while (map.size() > capacity) {
					Node<E> lru = deque.pollLast();
					if (lru != null) {
						map.remove(lru.key, lru);
					}
				}
			} finally {
				evictionLock.unlock();
			}
		}
	}

	private static class Node<E> {
		final E key;

		Node(E key) {
			this.key = key;
		}
	}
}