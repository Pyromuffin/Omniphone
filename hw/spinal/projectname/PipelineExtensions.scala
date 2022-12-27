package projectname

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline.Connection.M2S
import spinal.lib.pipeline._

object PipelineExtensions {



  implicit class TupleFlowExt[T1 <: Data, T2 <: Data](that : Flow[TupleBundle2[T1, T2]] ) {

    def pipeWith[OutputType <: Data, TExtra <: Data](pipeline: FixedLatencyPipeline[T1, OutputType]) = {

      pipeline.input << that.map(_._1)
      val extra = pipeline.propagate(that._2)
      pipeline.output.map{ out =>
        TupleBundle(out, extra)
      }
    }

    def >=>[OutputType <: Data](pipeline: FixedLatencyPipeline[T1, OutputType]) = {

        that.stage.pipeWith(pipeline)
    }

  }

  implicit class FlowExt[T <: Data](that : Flow[T]) {
    def pipe[OutputType <: Data](pipeline : FixedLatencyPipeline[T, OutputType]) = {

      pipeline.input << that
      pipeline.output
    }


    def >->[OutputType <: Data](pipeline: FixedLatencyPipeline[T, OutputType]) = {

      pipeline.input <-< that
      pipeline.output
    }

    def >~>[TransformedType <: Data]( op : T => TransformedType ) = {

      that.stage.map(op)
    }

  }


  case class PipelineStream[InputType <: Data, OutputType <: Data] (push : Stream[InputType], pop : Stream[OutputType])

  abstract class FixedLatencyPipeline[InputType <: Data, OutputType <: Data] extends Component {

    val input : Flow[InputType]
    val output : Flow[OutputType]

    def latency = spinal.lib.LatencyAnalysis(input.valid, output.valid)

    def propagate[T <: Data]( data : T) = {
      Delay(data, latency)
    }

    def propagateWith[T <: Data]( data : T) = {
      val propagated = propagate(data)
      output.translateWith(TupleBundle(output.payload, propagated))
    }


    def streamWithSkidBuffer() = {
      val skidBuffer = StreamFifo(output.payload, latency + 3) // +3 means, one for input register, one for output register, one for input to output fifo latency
      skidBuffer.setPartialName("skidbuffer")
      val inputStream = Stream(input.payload)
      val outputStream = Stream(output.payload)

      inputStream.ready := outputStream.ready
      inputStream.toFlowFire >-> input

      output.toStream >-> skidBuffer.io.push
      skidBuffer.io.pop >-> outputStream

      PipelineStream(inputStream, outputStream)
    }


    def streamWithClockEnable() = {
      val inputStream = Stream(input.payload)
      val outputStream = Stream(output.payload)

      inputStream.ready := outputStream.ready
      inputStream.toFlowFire >-> input

      val enable = outputStream.ready
      output.stage.toStream >> outputStream

      // this probably doesn't work....
      this.clockDomain.clockEnable := enable

      PipelineStream(inputStream, outputStream)
    }


  }


  implicit class DataExt[DataType <: Data](that: DataType) {
    def |~ = new StageableWithData[DataType](that)

    def toFlow(valid : Bool) = {
      val flow = Flow(that)
      flow.payload := that
      flow.valid := valid
      flow
    }

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
