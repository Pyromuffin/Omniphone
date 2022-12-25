package projectname

import spinal.core._
import spinal.lib.pipeline.Connection.M2S
import spinal.lib.pipeline._

object PipelineExtensions {

  implicit class DataExt[DataType <: Data](that: DataType) {
    def |~ = new StageableWithData[DataType](that)
  }

  class StageableWithData[T <: Data](gen: => T) extends Stageable(gen) {
    val data = gen
  }

  class StageArea(implicit _pip: Pipeline) extends Stage {

    ConnectToPrevious()

    def ConnectToPrevious(): Unit = {
      if (_pip.stagesSet.size > 1)
        _pip.connect(_pip.stagesSet.takeWhile(_ != this).last, this)(M2S())
    }

    override def valCallbackRec(obj: Any, name: String): Unit = {
      super.valCallbackRec(obj, name)

      obj match {
        case s: StageableWithData[_] => this (s) := s.data
        case _ =>
      }
    }
  }
}
