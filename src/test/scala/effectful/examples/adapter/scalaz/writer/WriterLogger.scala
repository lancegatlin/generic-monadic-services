package effectful.examples.adapter.scalaz.writer

import java.time.Instant

import effectful.examples.effects.logging.Logger

import scalaz.{Writer, WriterT}

class WriterLogger(name: String) extends Logger[LogWriter] {
  override def trace(message: =>String): LogWriter[Unit] =
    WriterT.tell(LogEntry(name,LogLevel.Trace,message,None,Instant.now()) :: Nil)

  override def trace(message: =>String, cause: Throwable): LogWriter[Unit] =
    Writer(LogEntry(name,LogLevel.Trace,message,Some(cause),Instant.now()) :: Nil,())

  override def debug(message: =>String): LogWriter[Unit] =
    Writer(LogEntry(name,LogLevel.Debug,message,None,Instant.now()) :: Nil,())

  override def debug(message: =>String, cause: Throwable): LogWriter[Unit] =
    Writer(LogEntry(name,LogLevel.Debug,message,Some(cause),Instant.now()) :: Nil,())

  override def info(message: =>String): LogWriter[Unit] =
    Writer(LogEntry(name,LogLevel.Info,message,None,Instant.now()) :: Nil,())

  override def info(message: => String, cause: Throwable): LogWriter[Unit] =
    Writer(LogEntry(name,LogLevel.Info,message,None,Instant.now()) :: Nil,())

  override def warn(message: =>String): LogWriter[Unit] =
    Writer(LogEntry(name,LogLevel.Warn,message,None,Instant.now()) :: Nil,())

  override def warn(message: =>String, cause: Throwable): LogWriter[Unit] =
    Writer(LogEntry(name,LogLevel.Warn,message,Some(cause),Instant.now()) :: Nil,())

  override def error(message: =>String): LogWriter[Unit] =
    Writer(LogEntry(name,LogLevel.Error,message,None,Instant.now()) :: Nil,())

  override def error(message: =>String, cause: Throwable): LogWriter[Unit] =
    Writer(LogEntry(name,LogLevel.Error,message,Some(cause),Instant.now()) :: Nil,())
}

object WriterLogger {
  def apply(name: String) : WriterLogger =
    new WriterLogger(name)
}