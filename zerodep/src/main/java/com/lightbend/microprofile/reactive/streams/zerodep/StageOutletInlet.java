/*******************************************************************************
 * Copyright (c) 2018 Lightbend Inc.
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.lightbend.microprofile.reactive.streams.zerodep;

import java.util.Objects;

/**
 * A stage outlet and inlet. Elements passed in to the outlet are forwarded to the inlet, and backpressure from the
 * inlet flows to the outlet.
 *
 * This port is for use between two stages of a graph.
 */
final class StageOutletInlet<T> implements Port {
  private final BuiltGraph builtGraph;

  private InletListener inletListener;
  private OutletListener outletListener;
  private boolean inletPulled;
  /**
   * The pushed element is an element that has been pushed but for which onPush has not yet been invoked. Once onPush
   * is invoked, it is transferred to currentElement. The reason for this separation is that pushing of elements is not
   * done directly, in order to avoid infinite recursions between stages doing a push/pull back and forth.
   */
  private T pushedElement;
  private T currentElement;
  private boolean outletFinished;
  private boolean inletFinished;
  private Throwable failure;

  StageOutletInlet(BuiltGraph builtGraph) {
    this.builtGraph = builtGraph;
  }

  @Override
  public void onStreamFailure(Throwable reason) {
    if (!outletFinished) {
      outletFinished = true;
      if (outletListener != null) {
        outletListener.onDownstreamFinish();
      }
    }
    if (!inletFinished) {
      inletFinished = true;
      if (inletListener != null) {
        inletListener.onUpstreamFailure(reason);
      }
    }
  }

  @Override
  public void verifyReady() {
    if (inletListener == null) {
      throw new IllegalStateException("Cannot start stream without inlet listener set");
    }
    if (outletListener == null) {
      throw new IllegalStateException("Cannot start stream without outlet listener set");
    }
  }

  final class Outlet implements StageOutlet<T>, UnrolledSignal {
    @Override
    public void push(T element) {
      Objects.requireNonNull(element, "Elements cannot be null");
      if (outletFinished) {
        throw new IllegalStateException("Can't push element after complete");
      } else if (!inletPulled || currentElement != null || pushedElement != null) {
        throw new IllegalStateException("Can't push element to outlet when it hasn't pulled");
      } else {
        pushedElement = element;
        builtGraph.enqueueSignal(this);
      }
    }

    @Override
    public void signal() {
      if (!inletFinished) {
        currentElement = pushedElement;
        pushedElement = null;
        inletListener.onPush();
        // Possible that there was a pull/push cycle done during that onPush,
        // followed by a complete, in which case, we don't want to publish that
        // complete yet.
        if (outletFinished && pushedElement == null && !inletFinished) {
          inletFinished = true;
          if (failure != null) {
            inletListener.onUpstreamFailure(failure);
            failure = null;
          } else {
            inletListener.onUpstreamFinish();
          }
        }
      }
    }

    @Override
    public boolean isAvailable() {
      return !outletFinished && inletPulled && pushedElement == null && currentElement == null;
    }

    @Override
    public void complete() {
      if (outletFinished) {
        throw new IllegalStateException("Can't complete twice.");
      }
      outletFinished = true;
      inletPulled = false;
      if (pushedElement == null && currentElement == null && !inletFinished) {
        inletFinished = true;
        inletListener.onUpstreamFinish();
      }
    }

    @Override
    public boolean isClosed() {
      return outletFinished;
    }

    @Override
    public void fail(Throwable error) {
      Objects.requireNonNull(error, "Error must not be null");
      if (outletFinished) {
        throw new IllegalStateException("Can't complete twice.");
      }
      outletFinished = true;
      inletPulled = false;
      if (pushedElement == null && currentElement == null && !inletFinished) {
        inletFinished = true;
        inletListener.onUpstreamFailure(error);
      } else {
        failure = error;
      }
    }

    @Override
    public void setListener(OutletListener listener) {
      outletListener = Objects.requireNonNull(listener, "Cannot register null listener");
    }
  }

  final class Inlet implements StageInlet<T> {

    @Override
    public void pull() {
      if (inletFinished) {
        throw new IllegalStateException("Can't pull after complete");
      } else if (inletPulled) {
        throw new IllegalStateException("Can't pull twice");
      } else if (currentElement != null) {
        throw new IllegalStateException("Can't pull without having grabbed the previous element");
      }
      if (!outletFinished) {
        inletPulled = true;
        outletListener.onPull();
      }
    }

    @Override
    public boolean isPulled() {
      return inletPulled;
    }

    @Override
    public boolean isAvailable() {
      return currentElement != null;
    }

    @Override
    public boolean isClosed() {
      return inletFinished;
    }

    @Override
    public void cancel() {
      if (inletFinished) {
        throw new IllegalStateException("Stage already finished");
      }
      inletFinished = true;
      currentElement = null;
      inletPulled = false;
      if (!outletFinished) {
        outletFinished = true;
        outletListener.onDownstreamFinish();
      }
    }

    @Override
    public T grab() {
      if (currentElement == null) {
        throw new IllegalStateException("Grab without onPush notification");
      }
      T grabbed = currentElement;
      inletPulled = false;
      currentElement = null;
      return grabbed;
    }

    @Override
    public void setListener(InletListener listener) {
      inletListener = Objects.requireNonNull(listener, "Cannot register null listener");
    }
  }
}
