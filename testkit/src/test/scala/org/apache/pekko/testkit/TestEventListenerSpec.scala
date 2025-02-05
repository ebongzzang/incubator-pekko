/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, derived from Akka.
 */

/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.testkit

import org.apache.pekko
import pekko.event.Logging
import pekko.event.Logging.Error
import pekko.event.Logging.Warning

class TestEventListenerSpec extends PekkoSpec with ImplicitSender {

  "The classic EventFilter.error" must {
    "filter errors without cause" in {
      val filter = EventFilter.error()
      filter(errorNoCause) should ===(true)
    }

    "filter errors with cause" in {
      val filter = EventFilter.error()
      filter(errorWithCause(new AnError)) should ===(true)
    }
  }

  "The typed EventFilter" must {
    "not filter errors without cause" in {
      val filter = EventFilter[AnError]()
      filter(errorNoCause) should ===(false)
    }

    "not filter errors with an unrelated cause" in {
      object AnotherError extends Exception
      val filter = EventFilter[AnError]()
      filter(errorWithCause(AnotherError)) should ===(false)
    }

    "filter errors with a matching cause" in {
      val filter = EventFilter[AnError]()
      filter(errorWithCause(new AnError)) should ===(true)
    }
  }

  "The EventFilter[NoCause]" must {
    "filter errors without cause" in {
      val filter = EventFilter[Error.NoCause.type]()
      filter(errorNoCause) should ===(true)
    }

    // The current implementation could be improved. See https://github.com/akka/akka/pull/25125#discussion_r189810017
    "filter errors with (any) cause" in {
      val filter = EventFilter[Error.NoCause.type]()
      filter(errorWithCause(new AnError)) should ===(true)
    }
  }

  "The classic EventFilter.warning" must {
    "filter warnings without cause" in {
      val filter = EventFilter.warning()
      filter(warningNoCause) should ===(true)
    }
    "filter warning with cause" in {
      val filter = EventFilter.warning()
      filter(warningWithCause(new AnError)) should ===(true)
    }
  }

  private class AnError extends Exception
  private def errorNoCause = Error(self.path.toString, this.getClass, "this is an error")
  private def errorWithCause(cause: Throwable) = Error(cause, self.path.toString, this.getClass, "this is an error")

  private def warningNoCause = Warning(self.path.toString, this.getClass, "this is a warning")
  private def warningWithCause(cause: Throwable) =
    Warning(cause, self.path.toString, this.getClass, "this is a warning", Logging.emptyMDC)
}
