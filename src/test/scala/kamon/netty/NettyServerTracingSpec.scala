package kamon.netty

import kamon.Kamon
import kamon.testkit.{MetricInspection, Reconfigure, TestSpanReporter}
import kamon.trace.Span.TagValue
import kamon.util.Registration
import org.scalatest.concurrent.Eventually
import org.scalatest.time.SpanSugar._
import org.scalatest._

class NettyServerTracingSpec extends WordSpec with Matchers with MetricInspection with Eventually
  with Reconfigure with BeforeAndAfterAll with OptionValues {

  "The Netty Server request span propagation" should {
    "propagate the span from the client to the server" in {
      Servers.withNioServer() { port =>
        Clients.withNioClient(port) { httpClient =>
          val testSpan =  Kamon.buildSpan("client-span").start()
          Kamon.withActiveSpan(testSpan) {
            val httpGet = httpClient.get(s"http://localhost:$port/route?param=123")
            httpClient.execute(httpGet)

            eventually(timeout(2 seconds)) {
              val serverFinishedSpan = reporter.nextSpan().value
              val clientFinishedSpan = reporter.nextSpan().value

              serverFinishedSpan.operationName shouldBe s"http://localhost:$port/route?param=123"
              serverFinishedSpan.tags should contain ("span.kind" -> TagValue.String("server"))

              clientFinishedSpan.operationName shouldBe s"http://localhost:$port/route?param=123"
              clientFinishedSpan.tags should contain ("span.kind" -> TagValue.String("client"))

              serverFinishedSpan.context.parentID shouldBe clientFinishedSpan.context.spanID

              reporter.nextSpan() shouldBe empty
            }
          }
        }
      }
    }

    "contain a span error when a Internal Server Error(500) occurs" in {
      Servers.withNioServer() { port =>
        Clients.withNioClient(port) { httpClient =>
          val testSpan =  Kamon.buildSpan("test-span-with-error").start()
          Kamon.withActiveSpan(testSpan) {
            val httpGet = httpClient.get(s"http://localhost:$port/error")
            httpClient.execute(httpGet)

            eventually(timeout(2 seconds)) {
              val serverFinishedSpan = reporter.nextSpan().value
              val clientFinishedSpan = reporter.nextSpan().value

              serverFinishedSpan.operationName shouldBe s"http://localhost:$port/error"
              serverFinishedSpan.tags should contain allOf("span.kind" -> TagValue.String("server"), "error" -> TagValue.String("true"))

              clientFinishedSpan.tags should contain ("span.kind" -> TagValue.String("client"))
              clientFinishedSpan.operationName shouldBe s"http://localhost:$port/error"
            }
          }
        }
      }
    }

    "propagate the span from the client to the server with chunk-encoded request" in {
      Servers.withNioServer() { port =>
        Clients.withNioClient(port) { httpClient =>
          val testSpan = Kamon.buildSpan("client-chunk-span").start()
          Kamon.withActiveSpan(testSpan) {
            val (httpPost, chunks) = httpClient.postWithChunks(s"http://localhost:$port/route?param=123", "test 1", "test 2")
            httpClient.executeWithContent(httpPost, chunks)

            eventually(timeout(2 seconds)) {
              val serverFinishedSpan = reporter.nextSpan().value
              val clientFinishedSpan = reporter.nextSpan().value

              serverFinishedSpan.operationName shouldBe s"http://localhost:$port/route?param=123"
              serverFinishedSpan.tags should contain ("span.kind" -> TagValue.String("server"))

              clientFinishedSpan.operationName shouldBe s"http://localhost:$port/route?param=123"
              clientFinishedSpan.tags should contain ("span.kind" -> TagValue.String("client"))

              serverFinishedSpan.context.parentID shouldBe clientFinishedSpan.context.spanID

              reporter.nextSpan() shouldBe empty
            }
          }
        }
      }
    }

    "propagate the span from the client to the server with chunk-encoded response" in {
      Servers.withNioServer() { port =>
        Clients.withNioClient(port) { httpClient =>
          val testSpan = Kamon.buildSpan("client-chunk-span").start()
          Kamon.withActiveSpan(testSpan) {
            val (httpPost, chunks) = httpClient.postWithChunks(s"http://localhost:$port/fetch-in-chunks", "test 1", "test 2")
            httpClient.executeWithContent(httpPost, chunks)

            eventually(timeout(2 seconds)) {
              val serverFinishedSpan = reporter.nextSpan().value
              val clientFinishedSpan = reporter.nextSpan().value

              serverFinishedSpan.operationName shouldBe s"http://localhost:$port/fetch-in-chunks"
              serverFinishedSpan.tags should contain ("span.kind" -> TagValue.String("server"))

              clientFinishedSpan.operationName shouldBe s"http://localhost:$port/fetch-in-chunks"
              clientFinishedSpan.tags should contain ("span.kind" -> TagValue.String("client"))

              serverFinishedSpan.context.parentID shouldBe clientFinishedSpan.context.spanID

              reporter.nextSpan() shouldBe empty
            }
          }
        }
      }
    }

    "create a new span when it's coming a request without one" in {
      Servers.withNioServer() { port =>
        Clients.withNioClient(port) { httpClient =>
          val httpGet = httpClient.get(s"http://localhost:$port/route?param=123")
          httpClient.execute(httpGet)

          eventually(timeout(2 seconds)) {
            val serverFinishedSpan = reporter.nextSpan().value

            serverFinishedSpan.operationName shouldBe s"http://localhost:$port/route?param=123"
            serverFinishedSpan.tags should contain ("span.kind" -> TagValue.String("server"))

            serverFinishedSpan.context.parentID.string shouldBe ""

            reporter.nextSpan() shouldBe empty
          }
        }
      }
    }
  }

  @volatile var registration: Registration = _
  val reporter = new TestSpanReporter()

  override protected def beforeAll(): Unit = {
    enableFastSpanFlushing()
    sampleAlways()
    registration = Kamon.addReporter(reporter)
  }

  override protected def afterAll(): Unit = {
    registration.cancel()
  }
}
