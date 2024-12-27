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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static java.util.Map.Entry;
import static java.util.Objects.requireNonNull;

/**
 * A threadsafe LRU {@link Map}.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class ConcurrentLruMap<K, V> implements Map<K, V> {
	@Nonnull
	private final Integer capacity;
	@Nonnull
	private final BiConsumer<K, V> evictionListener;
	@Nonnull
	private final Lock readLock;
	@Nonnull
	private final Lock writeLock;

	/**
	 * A LinkedHashMap with access-order and custom eviction behavior.
	 * When the size exceeds 'capacity', the eldest entry is removed,
	 * and 'evictionListener' is invoked.
	 */
	@Nonnull
	private final LinkedHashMap<K, V> backingMap;

	public ConcurrentLruMap(@Nonnull Integer capacity,
													@Nonnull BiConsumer<K, V> evictionListener) {
		requireNonNull(capacity);
		requireNonNull(evictionListener);

		if (capacity <= 0)
			throw new IllegalArgumentException("Capacity must be greater than 0");

		ReentrantReadWriteLock reentrantReadWriteLock = new ReentrantReadWriteLock();

		this.capacity = capacity;
		this.evictionListener = evictionListener;
		this.readLock = reentrantReadWriteLock.readLock();
		this.writeLock = reentrantReadWriteLock.writeLock();

		// Access-order = true -> get operations move entry to most-recently-used position
		this.backingMap = new LinkedHashMap<K, V>(capacity, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(@Nonnull Entry<K, V> eldest) {
				if (size() > getCapacity()) {
					evictionListener.accept(eldest.getKey(), eldest.getValue());
					return true;
				}

				return false;
			}
		};
	}

	@Override
	public String toString() {
		getReadLock().lock();
		try {
			return getBackingMap().toString();
		} finally {
			getReadLock().unlock();
		}
	}

	@Override
	public int size() {
		getReadLock().lock();
		try {
			return getBackingMap().size();
		} finally {
			getReadLock().unlock();
		}
	}

	@Override
	public boolean isEmpty() {
		getReadLock().lock();
		try {
			return getBackingMap().isEmpty();
		} finally {
			getReadLock().unlock();
		}
	}

	@Override
	public boolean containsKey(Object key) {
		getReadLock().lock();
		try {
			return getBackingMap().containsKey(key);
		} finally {
			getReadLock().unlock();
		}
	}

	@Override
	public boolean containsValue(Object value) {
		getReadLock().lock();
		try {
			return getBackingMap().containsValue(value);
		} finally {
			getReadLock().unlock();
		}
	}

	@Override
	public V get(Object key) {
		getReadLock().lock();
		try {
			return getBackingMap().get(key);
		} finally {
			getReadLock().unlock();
		}
	}

	/**
	 * Returns the value to which the specified key is mapped, or
	 * {@code defaultValue} if this map contains no mapping for the key.
	 */
	@Override
	public V getOrDefault(Object key, V defaultValue) {
		getReadLock().lock();
		try {
			return getBackingMap().getOrDefault(key, defaultValue);
		} finally {
			getReadLock().unlock();
		}
	}

	@Override
	public V put(K key, V value) {
		getWriteLock().lock();
		try {
			return getBackingMap().put(key, value);
		} finally {
			getWriteLock().unlock();
		}
	}

	/**
	 * If the specified key is not already associated with a value, associate it
	 * with the given value and return null, else return the current value.
	 */
	@Override
	public V putIfAbsent(K key, V value) {
		getWriteLock().lock();
		try {
			return getBackingMap().putIfAbsent(key, value);
		} finally {
			getWriteLock().unlock();
		}
	}

	/**
	 * If the specified key is not already associated with a value (or is mapped
	 * to null), attempts to compute its value using the given mapping function
	 * and enters it into this map unless null.
	 */
	@Override
	public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
		getWriteLock().lock();
		try {
			return getBackingMap().computeIfAbsent(key, mappingFunction);
		} finally {
			getWriteLock().unlock();
		}
	}

	@Override
	public V remove(Object key) {
		getWriteLock().lock();
		try {
			return getBackingMap().remove(key);
		} finally {
			getWriteLock().unlock();
		}
	}

	@Override
	public void putAll(Map<? extends K, ? extends V> m) {
		getWriteLock().lock();
		try {
			for (Map.Entry<? extends K, ? extends V> entry : m.entrySet()) {
				getBackingMap().put(entry.getKey(), entry.getValue());
			}
		} finally {
			getWriteLock().unlock();
		}
	}

	@Override
	public void clear() {
		getWriteLock().lock();
		try {
			getBackingMap().clear();
		} finally {
			getWriteLock().unlock();
		}
	}

	/**
	 * Returns a snapshot of the keys in this map at the time of calling.
	 * Iterating over this set will not reflect subsequent modifications.
	 */
	@Override
	public Set<K> keySet() {
		getReadLock().lock();
		try {
			return new LinkedHashSet<>(getBackingMap().keySet());
		} finally {
			getReadLock().unlock();
		}
	}

	/**
	 * Returns a snapshot of the values in this map at the time of calling.
	 * Iterating over this collection will not reflect subsequent modifications.
	 */
	@Override
	public Collection<V> values() {
		getReadLock().lock();
		try {
			return new ArrayList<>(getBackingMap().values());
		} finally {
			getReadLock().unlock();
		}
	}

	/**
	 * Returns a snapshot of the entries in this map at the time of calling.
	 * Iterating over this set will not reflect subsequent modifications.
	 */
	@Override
	public Set<Map.Entry<K, V>> entrySet() {
		getReadLock().lock();
		try {
			return new LinkedHashSet<>(getBackingMap().entrySet());
		} finally {
			getReadLock().unlock();
		}
	}

	@Nonnull
	protected Integer getCapacity() {
		return this.capacity;
	}

	@Nonnull
	protected BiConsumer<K, V> getEvictionListener() {
		return this.evictionListener;
	}

	@Nonnull
	protected Lock getReadLock() {
		return this.readLock;
	}

	@Nonnull
	protected Lock getWriteLock() {
		return this.writeLock;
	}

	@Nonnull
	protected LinkedHashMap<K, V> getBackingMap() {
		return this.backingMap;
	}
}