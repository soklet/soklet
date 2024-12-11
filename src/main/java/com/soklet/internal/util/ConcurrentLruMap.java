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


import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * A threadsafe LRU {@link Map}.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class ConcurrentLruMap<K, V> implements Map<K, V> {
	private final int capacity;
	private final ConcurrentHashMap<K, Node<K, V>> map;
	private final ConcurrentLinkedDeque<Node<K, V>> deque;
	private final ReentrantLock evictionLock;
	private final BiConsumer<K, V> evictionListener;

	public ConcurrentLruMap(int capacity) {
		this(capacity, null);
	}

	public ConcurrentLruMap(int capacity,
													BiConsumer<K, V> evictionListener) {
		if (capacity <= 0) {
			throw new IllegalArgumentException("Capacity must be greater than zero");
		}
		this.capacity = capacity;
		this.map = new ConcurrentHashMap<>(capacity);
		this.deque = new ConcurrentLinkedDeque<>();
		this.evictionLock = new ReentrantLock();
		this.evictionListener = evictionListener != null ? evictionListener : (k, v) -> { /* no-op */ };
	}

	@Override
	public int size() {
		return map.size();
	}

	@Override
	public boolean isEmpty() {
		return map.isEmpty();
	}

	@Override
	public boolean containsKey(Object key) {
		return map.containsKey(key);
	}

	@Override
	public boolean containsValue(Object value) {
		for (Node<K, V> node : map.values()) {
			if (Objects.equals(node.value, value)) {
				return true;
			}
		}
		return false;
	}

	@Override
	@SuppressWarnings("unchecked")
	public V get(Object key) {
		Node<K, V> node = map.get((K) key);
		if (node == null) {
			return null;
		}
		// Move to MRU position
		deque.remove(node);
		deque.addFirst(node);
		return node.value;
	}

	/**
	 * Returns the value associated with the key, or defaultValue if not present.
	 * If the key is present, moves it to MRU position.
	 */
	@Override
	public V getOrDefault(Object key, V defaultValue) {
		Node<K, V> node = map.get(key);
		if (node == null) {
			return defaultValue;
		}
		deque.remove(node);
		deque.addFirst(node);
		return node.value;
	}

	@Override
	public V put(K key, V value) {
		Node<K, V> newNode = new Node<>(key, value);
		Node<K, V> oldNode = map.put(key, newNode);

		if (oldNode != null) {
			deque.remove(oldNode);
		}
		deque.addFirst(newNode);

		V oldValue = (oldNode != null) ? oldNode.value : null;
		evictIfNeeded();
		return oldValue;
	}

	/**
	 * Inserts the key-value pair only if the key is not already present.
	 * If the key is present, returns the existing value and moves that entry to MRU.
	 */
	@Override
	public V putIfAbsent(K key, V value) {
		Node<K, V> newNode = new Node<>(key, value);
		Node<K, V> existing = map.putIfAbsent(key, newNode);
		if (existing == null) {
			// Newly inserted
			deque.addFirst(newNode);
			evictIfNeeded();
			return null;
		} else {
			// Key existed, move to MRU
			deque.remove(existing);
			deque.addFirst(existing);
			return existing.value;
		}
	}

	/**
	 * If the key is not already present, attempts to compute its value using the given mappingFunction.
	 * If a value is computed (not null), inserts it and returns it.
	 * If the key exists, returns the existing value and moves it to MRU.
	 * If the mapping function returns null, returns null and does not insert.
	 */
	public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
		// Atomically compute or retrieve the node
		Node<K, V> node = map.computeIfAbsent(key, k -> {
			V newVal = mappingFunction.apply(k);
			return (newVal == null) ? null : new Node<>(k, newVal);
		});

		if (node == null) {
			// No entry was inserted (mappingFunction returned null), return null
			return null;
		}

		// Node is present (either existing or newly created)
		// Move it to MRU position
		deque.remove(node);
		deque.addFirst(node);

		// If newly created, we might need to evict
		evictIfNeeded();
		return node.value;
	}

	@Override
	@SuppressWarnings("unchecked")
	public V remove(Object key) {
		Node<K, V> node = map.remove((K) key);
		if (node == null) {
			return null;
		}
		deque.remove(node);
		return node.value;
	}

	@Override
	public void putAll(Map<? extends K, ? extends V> m) {
		for (Entry<? extends K, ? extends V> e : m.entrySet()) {
			put(e.getKey(), e.getValue());
		}
	}

	@Override
	public void clear() {
		map.clear();
		deque.clear();
	}

	@Override
	public Set<K> keySet() {
		return Collections.unmodifiableSet(map.keySet());
	}

	@Override
	public Collection<V> values() {
		List<V> vals = new ArrayList<>(map.size());
		for (Node<K, V> node : map.values()) {
			vals.add(node.value);
		}
		return Collections.unmodifiableList(vals);
	}

	@Override
	public Set<Entry<K, V>> entrySet() {
		Set<Entry<K, V>> entries = new HashSet<>();
		for (Map.Entry<K, Node<K, V>> e : map.entrySet()) {
			entries.add(new AbstractMap.SimpleEntry<>(e.getKey(), e.getValue().value));
		}
		return Collections.unmodifiableSet(entries);
	}

	private void evictIfNeeded() {
		if (map.size() > capacity) {
			Node<K, V> evictedNode = null;

			evictionLock.lock();

			try {
				while (map.size() > capacity) {
					Node<K, V> tailNode = deque.pollLast();

					if (tailNode != null && map.remove(tailNode.key, tailNode))
						evictedNode = tailNode;
				}
			} finally {
				evictionLock.unlock();
			}

			if (evictedNode != null)
				getEvictionListener().accept(evictedNode.key, evictedNode.value);
		}
	}

	private static class Node<K, V> {
		final K key;
		volatile V value;

		Node(K key, V value) {
			this.key = key;
			this.value = value;
		}
	}

	@Nonnull
	protected BiConsumer<K, V> getEvictionListener() {
		return this.evictionListener;
	}
}