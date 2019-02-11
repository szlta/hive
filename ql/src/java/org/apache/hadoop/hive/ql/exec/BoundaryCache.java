/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.exec;

import java.util.LinkedList;
import java.util.Map;
import java.util.TreeMap;

/**
 * Cache for storing boundaries found within a partition - used for PTF functions.
 * Stores key-value pairs where key is the row index in the partition from which a range begins,
 * value is the corresponding row value (based on what the user specified in the orderby column).
 */
public class BoundaryCache extends TreeMap<Integer, Object> {

  private boolean isComplete = false;
  private final int maxSize;
  private final LinkedList<Integer> queue = new LinkedList<>();

  public BoundaryCache(int maxSize) {
    if (maxSize <= 1) {
      throw new IllegalArgumentException("Cache size of 1 and below it doesn't make sense.");
    }
    this.maxSize = maxSize;
  }

  /**
   * True if the last range(s) of the partition are loaded into the cache.
   * @return
   */
  public boolean isComplete() {
    return isComplete;
  }

  public void setComplete(boolean complete) {
    isComplete = complete;
  }

  @Override
  public Object put(Integer key, Object value) {
    Object result = super.put(key, value);
    //Every new element is added to FIFO too.
    if (result == null) {
      queue.add(key);
    }
    //If FIFO size reaches maxSize we evict the eldest entry.
    if (queue.size() > maxSize) {
      evictOne();
    }
    return result;
  }

  /**
   * Puts new key-value pair in cache.
   * @param key
   * @param value
   * @return false if queue was full and put failed. True otherwise.
   */
  public Boolean putIfNotFull(Integer key, Object value) {
    if ((queue.size() + 1) > maxSize) {
      return false;
    } else {
      put(key, value);
      return true;
    }
  }

  @Override
  public void clear() {
    this.isComplete = false;
    this.queue.clear();
    super.clear();
  }

  /**
   * Evicts the older half of cache.
   */
  public void evictHalf() {
    int evictCount = queue.size() / 2;
    for (int i = 0; i < evictCount; ++i) {
      evictOne();
    }
  }

  /**
   * Calculates the percentile of the row's group position in the cache, 0 means cache
   * beginning, 100 means cache end.
   *
   * @param pos
   * @return percentile.
   */
  public int approxCachePositionOf(int pos) {
    if (size() == 0) {
      return 0;
    }
    Map.Entry<Integer, Object> floorEntry = floorEntry(pos);
    if (floorEntry == null) {
      return 100;
    } else{
      //Using the fact, that queue is always filled from bottom to top in a partition
      return (100 * (queue.indexOf(floorEntry.getKey()) + 1)) / maxSize;
    }
  }

  /**
   * Returns entry corresponding to highest row index.
   * @return max entry.
   */
  public Map.Entry<Integer, Object> getMaxEntry() {
    return floorEntry(Integer.MAX_VALUE);
  }

  /**
   * Removes eldest entry from the boundary cache.
   */
  private void evictOne() {
    Integer elementToDelete = queue.poll();
    this.remove(elementToDelete);
  }

}
