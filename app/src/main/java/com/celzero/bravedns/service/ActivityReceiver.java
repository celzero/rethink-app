/*
Copyright 2018 Jigsaw Operations LLC

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.celzero.bravedns.service;

import java.util.Collection;

/**
 * Interface for classes that can receive information about when recent events occurred.
 *
 * This class primarily serves to allow HistoryGraph to scan QueryTracker's activity data
 * while QueryTracker holds a lock, without making an extra copy and without QueryTracker
 * having to import HistoryGraph.
 */
public interface ActivityReceiver {
  /**
   * @param activity The SystemClock.elapsedRealtime() timestamps of each event in the recent
   *                 activity history as an unmodifiable collection.  The implementor must not
   *                 retain the argument past the end of this call, as it is owned by the caller
   *                 and may be modified.
   */
  void receive(Collection<Long> activity);
}
