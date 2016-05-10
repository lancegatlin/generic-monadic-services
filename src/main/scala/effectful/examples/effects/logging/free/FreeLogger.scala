package effectful.examples.effects.logging.free

import effectful.Free
import effectful.examples.effects.logging.Logger

class FreeLogger(logger: String) extends Logger[FreeLoggingCmd] {
  override def trace(message: =>String) =
    Free.Cmd(LoggingCmd.Trace(logger,message,None))

  override def trace(message: =>String, cause: Throwable): FreeLoggingCmd[Unit] =
    Free.Cmd(LoggingCmd.Trace(logger,message,Some(cause)))

  override def debug(message: =>String): FreeLoggingCmd[Unit] =
    Free.Cmd(LoggingCmd.Debug(logger,message,None))

  override def debug(message: =>String, cause: Throwable): FreeLoggingCmd[Unit] =
    Free.Cmd(LoggingCmd.Debug(logger,message,Some(cause)))

  override def info(message: =>String): FreeLoggingCmd[Unit] =
    Free.Cmd(LoggingCmd.Info(logger,message,None))

  override def info(message: => String, cause: Throwable): FreeLoggingCmd[Unit] =
    Free.Cmd(LoggingCmd.Info(logger,message,Some(cause)))

  override def warn(message: =>String): FreeLoggingCmd[Unit] =
    Free.Cmd(LoggingCmd.Warn(logger,message,None))

  override def warn(message: =>String, cause: Throwable): FreeLoggingCmd[Unit] =
    Free.Cmd(LoggingCmd.Warn(logger,message,Some(cause)))

  override def error(message: =>String): FreeLoggingCmd[Unit] =
    Free.Cmd(LoggingCmd.Error(logger,message,None))

  override def error(message: =>String, cause: Throwable): FreeLoggingCmd[Unit] =
    Free.Cmd(LoggingCmd.Error(logger,message,Some(cause)))
}
