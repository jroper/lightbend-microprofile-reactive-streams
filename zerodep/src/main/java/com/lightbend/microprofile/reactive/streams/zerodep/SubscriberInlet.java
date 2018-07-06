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

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * An inlet that is a subscriber.
 *
 * This is either the first inlet for a graph that has an inlet, or is used to connect a Processor or Subscriber stage
 * in a graph.
 */
final class SubscriberInlet<T> implements StageInlet<T>, Subscriber<T>, Port, UnrolledSignal {
  private final BuiltGraph builtGraph;
  private final int bufferHighWatermark;
  private final int bufferLowWatermark;

  private final Deque<T> elements = new ArrayDeque<>();
  private T elementToPush;
  private Subscription subscription;
  private int outstandingDemand;
  private InletListener listener;
  private boolean upstreamFinished;
  private boolean downstreamFinished;
  private Throwable error;
  private boolean pulled;

  SubscriberInlet(BuiltGraph builtGraph, int bufferHighWatermark, int bufferLowWatermark) {
    this.builtGraph = builtGraph;
    this.bufferHighWatermark = bufferHighWatermark;
    this.bufferLowWatermark = bufferLowWatermark;
  }

  @Override
  public void onStreamFailure(Throwable reason) {
    if (!upstreamFinished && subscription != null) {
      upstreamFinished = true;
      try {
        subscription.cancel();
      } catch (RuntimeException e) {
        // Ignore
      }
      subscription = null;
      if (!downstreamFinished) {
        downstreamFinished = true;
        listener.onUpstreamFailure(reason);
      }
    }
  }

  @Override
  public void verifyReady() {
    if (listener == null) {
      throw new IllegalStateException("Cannot start stream without inlet listener set");
    }
  }

  @Override
  public void onSubscribe(Subscription subscription) {
    Objects.requireNonNull(subscription, "Subscription must not be null");
    builtGraph.execute(() -> {
      if (upstreamFinished || downstreamFinished || this.subscription != null) {
        subscription.cancel();
      } else {
        this.subscription = subscription;
        maybeRequest();
      }
    });
  }

  private void maybeRequest() {
    if (!upstreamFinished) {
      int bufferSize = outstandingDemand + elements.size();
      if (bufferSize <= bufferLowWatermark) {
        int toRequest = bufferHighWatermark - bufferSize;
        subscription.request(toRequest);
        outstandingDemand += toRequest;
      }
    }
  }

  @Override
  public void onNext(T item) {
    Objects.requireNonNull(item, "Elements passed to onNext must not be null");
    builtGraph.execute(() -> {
      if (downstreamFinished || upstreamFinished) {
        // Ignore events after cancellation or complete
      } else if (outstandingDemand == 0) {
        onStreamFailure(new IllegalStateException("Element signalled without demand for it"));
      } else {
        outstandingDemand -= 1;
        elements.add(item);
        if (pulled && elementToPush == null) {
          builtGraph.enqueueSignal(this);
        }
      }
    });
  }

  @Override
  public void signal() {
    if (!downstreamFinished) {
      if (!elements.isEmpty() && elementToPush == null) {
        elementToPush = elements.poll();
        listener.onPush();
      } else if (upstreamFinished) {
        downstreamFinished = true;
        if (error == null) {
          listener.onUpstreamFinish();
        } else {
          listener.onUpstreamFailure(error);
          error = null;
        }
      }
    }
  }

  @Override
  public void onError(Throwable throwable) {
    Objects.requireNonNull(throwable, "Error passed to onError must not be null");
    builtGraph.execute(() -> {
      if (downstreamFinished || upstreamFinished) {
        // Ignore
      } else {
        subscription = null;
        if (elements.isEmpty()) {
          downstreamFinished = true;
          upstreamFinished = true;
          listener.onUpstreamFailure(throwable);
        } else {
          upstreamFinished = true;
          error = throwable;
        }
      }
    });
  }

  @Override
  public void onComplete() {
    builtGraph.execute(() -> {
      if (downstreamFinished || upstreamFinished) {
        // Ignore
      } else {
        subscription = null;
        if (elements.isEmpty()) {
          downstreamFinished = true;
          upstreamFinished = true;
          listener.onUpstreamFinish();
        } else {
          upstreamFinished = true;
        }
      }
    });
  }

  @Override
  public void pull() {
    if (downstreamFinished) {
      throw new IllegalStateException("Can't pull when finished");
    } else if (pulled) {
      throw new IllegalStateException("Can't pull twice");
    }
    pulled = true;
    if (!elements.isEmpty()) {
      builtGraph.enqueueSignal(this);
    }
  }

  @Override
  public boolean isPulled() {
    return pulled;
  }

  @Override
  public boolean isAvailable() {
    return !elements.isEmpty();
  }

  @Override
  public boolean isClosed() {
    return downstreamFinished;
  }

  @Override
  public void cancel() {
    if (downstreamFinished) {
      throw new IllegalStateException("Can't cancel twice");
    } else {
      downstreamFinished = true;
      upstreamFinished = true;
      error = null;
      elements.clear();
      if (subscription != null) {
        subscription.cancel();
        subscription = null;
      }
    }
  }

  @Override
  public T grab() {
    if (downstreamFinished) {
      throw new IllegalStateException("Can't grab when finished");
    } else if (!pulled) {
      throw new IllegalStateException("Can't grab when not pulled");
    } else if (elementToPush == null) {
      throw new IllegalStateException("Grab without onPush");
    } else {
      pulled = false;
      T element = elementToPush;
      elementToPush = null;
      // Signal another signal so that we can notify downstream complete after
      // it gets the element without pulling first.
      if (elements.isEmpty() && upstreamFinished) {
        builtGraph.enqueueSignal(this);
      } else {
        maybeRequest();
      }
      return element;
    }
  }

  @Override
  public void setListener(InletListener listener) {
    this.listener = Objects.requireNonNull(listener, "Listener must not be null");
  }
}
