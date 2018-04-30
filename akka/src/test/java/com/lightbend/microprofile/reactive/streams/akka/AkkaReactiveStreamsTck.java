/******************************************************************************
 * Licensed under Public Domain (CC0)                                         *
 *                                                                            *
 * To the extent possible under law, the person who associated CC0 with       *
 * this code has waived all copyright and related or neighboring              *
 * rights to this code.                                                       *
 *                                                                            *
 * You should have received a copy of the CC0 legalcode along with this       *
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.     *
 ******************************************************************************/

package com.lightbend.microprofile.reactive.streams.akka;

import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import org.eclipse.microprofile.reactive.streams.spi.ReactiveStreamsEngine;
import org.eclipse.microprofile.reactive.streams.tck.CancelStageVerification;
import org.eclipse.microprofile.reactive.streams.tck.FlatMapStageVerification;
import org.eclipse.microprofile.reactive.streams.tck.ReactiveStreamsTck;
import org.reactivestreams.tck.TestEnvironment;
import org.testng.annotations.AfterSuite;

/**
 * TCK verification for the {@link AkkaEngine} implementation of the {@link ReactiveStreamsEngine}.
 */
public class AkkaReactiveStreamsTck extends ReactiveStreamsTck<AkkaEngine> {

  public AkkaReactiveStreamsTck() {
    super(new TestEnvironment());
  }

  private ActorSystem system;
  private Materializer materializer;

  @AfterSuite
  public void shutdownActorSystem() {
    if (system != null) {
      system.terminate();
    }
  }

  @Override
  protected AkkaEngine createEngine() {
    system = ActorSystem.create();
    materializer = ActorMaterializer.create(system);
    return new AkkaEngine(materializer);
  }

  @Override
  protected boolean isEnabled(Object test) {
    return true;
    // Disabled due to https://github.com/akka/akka/issues/24719
//    return !(test instanceof FlatMapStageVerification.InnerSubscriberVerification) &&
        // Disabled due to https://github.com/akka/akka/pull/24749
  //      !(test instanceof CancelStageVerification.SubscriberVerification);
  }
}
