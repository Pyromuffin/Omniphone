package projectname

import projectname.Omniphone._
import spinal.core._
import spinal.lib._
import spinal.lib.pipeline.Connection.M2S
import spinal.lib.pipeline.{Pipeline, Stage, Stageable}
import PipelineExtensions._

import scala.language.postfixOps


object Omniphone {

  def QuantizeWave(array: Array[Double], pcmDepth : Int) = {

    array.map{ wave =>
      val scaled = ( (wave + 1) * 0.5) * ( (1L << pcmDepth) - 1L)
      scaled.toLong
    }

  }

  def CreateSineWaveTable(pcmDepth : Int, sampleCount : Int) = {
    val phasePerSample = 2 * scala.math.Pi / sampleCount // it is extremely possible to be off by one here.

    val wave = Array.ofDim[Double](sampleCount)
    for(i <- 0 until sampleCount) {
      wave(i) = scala.math.sin(i * phasePerSample)
    }

    QuantizeWave(wave, pcmDepth)
  }


  def Reduce(multiplied : UInt) = {
    // multiplied numbers are big, get the middle.
    val fourthSize = multiplied.getBitsWidth / 4
    multiplied(fourthSize, fourthSize * 2 bits)
  }


  def lerp(firstSample : UInt, secondSample : UInt, fraction : UInt) = new Area {

    val oneMinusFraction = 0xFFFFFFFFL - fraction
    val embiggened_first = firstSample @@ U(0, fraction.getBitsWidth bits)
    val embiggened_oneMinusFraction = U(0, firstSample.getBitsWidth bits) @@ oneMinusFraction
    val multiplied_first = embiggened_first * embiggened_oneMinusFraction
    val truncated_first = Reduce(multiplied_first)


    val embiggened_second = secondSample @@ U(0, fraction.getBitsWidth bits)
    val embiggened_fraction = U(0, secondSample.getBitsWidth bits) @@ fraction
    val multiplied_second = embiggened_second * embiggened_fraction
    val truncated_second = Reduce(multiplied_second)

    val roundedArea = Round(truncated_first + truncated_second)
    val rounded = roundedArea.ret


    println("rounded width "  + rounded.getBitsWidth)

    val ret = rounded(0, firstSample.getBitsWidth bits)
  }


  def Round( number : UInt ) = new Area {
    val width = number.getBitsWidth
    val integerPart = number(width/2 , width/2 bits)
    val fractionPart = number(0 , width/2 bits)
    val half = U(1) @@ U(0, width/2 - 1 bits)

    val ret = UInt(integerPart.getBitsWidth bits)

    when(fractionPart >= half) {
      ret := integerPart + 1
    } otherwise {
      ret := integerPart
    }
  }
}

// i dont actually know what frequency this is running at???

case class OmniphoneControls() extends Bundle {

  val wavetableIndicesPerSampleIntegerPart = UInt(32 bits)
  val wavetableIndicesPerSampleFractionPart = UInt(32 bits)
  val amplitude = UInt(32 bits)
}

case class OmniphoneWaveTableUpdate(pcmDepth : Int) extends Bundle {
  val pcm = UInt(pcmDepth bits)
  val index = UInt(10 bits)
}



case class Lerper(depth : Int) extends FixedLatencyPipeline[Vec[UInt], UInt] {

  val io = new Bundle {
    val numbers = slave Flow Vec(UInt(depth bits), 3)
    val lerped = master Flow UInt(depth bits)
  }

  override val input = io.numbers
  override val output = io.lerped

  implicit val pipe = new Pipeline

  val first = new StageArea {

    val firstSample = io.numbers.payload(0) |~
    val secondSample = io.numbers.payload(1) |~
    val fraction = io.numbers.payload(2) |~
    val oneMinusFraction = U(0xFFFFFFFFL) - fraction |~
  }

  first.valid := io.numbers.valid

  val second = new StageArea {
    val embiggened_first = first.firstSample @@ U(0, first.fraction.getBitsWidth bits) |~
    val embiggened_second = first.secondSample @@ U(0, first.fraction.getBitsWidth bits) |~

    val embiggened_oneMinusFraction = U(0, first.firstSample.getBitsWidth bits) @@ first.oneMinusFraction |~
    val embiggened_fraction = U(0, first.secondSample.getBitsWidth bits) @@ first.fraction |~
  }

  val third = new StageArea {
    val multiplied_first = second.embiggened_first * second.embiggened_oneMinusFraction |~
    val multiplied_second = second.embiggened_second * second.embiggened_fraction |~
  }

  val fourth = new StageArea {
    val truncated_first = Reduce(third.multiplied_first)   |~
    val truncated_second = Reduce(third.multiplied_second) |~
  }

  val fifth = new StageArea {
    val sum = fourth.truncated_first + fourth.truncated_second |~
  }

  val sixth = new StageArea {
    val rounded  = Round(fifth.sum).ret |~
  }

  io.lerped.payload := sixth(sixth.rounded)
  io.lerped.valid := sixth.valid


  pipe.build()

}


class WaveTableSampler(waveTableSampleCount : Int, pcmDepth : Int) extends FixedLatencyPipeline[UInt, UInt] {


  val io = new Bundle {
    val uv = slave Flow UInt(64 bits)
    val sample = master Flow UInt(pcmDepth bits)
  }

  override val input = io.uv
  override val output = io.sample


  // stores one cycle? maybe it could store half a cycle if we assume they're symmetric
  val sinTable = CreateSineWaveTable(pcmDepth, waveTableSampleCount)
  val waveTable = Mem(UInt(pcmDepth bits), 1024) init sinTable.map(U(_, pcmDepth bits))

  val integerPart = io.uv.payload(32, 32 bits).resize( log2Up(waveTableSampleCount) bits)
  val fractionPart = io.uv.payload(0, 32 bits)

  val firstSample = waveTable.readSync(integerPart, io.uv.fire)
  val secondSample = waveTable.readSync(integerPart + 1, io.uv.fire)

  val samplesValid = RegNext(io.uv.fire) init False

  val lerper = Lerper(pcmDepth)

  lerper.io.numbers.payload(0) := firstSample
  lerper.io.numbers.payload(1) := secondSample
  lerper.io.numbers.payload(2) := RegNext(fractionPart)
  lerper.io.numbers.valid := samplesValid

  io.sample <-< lerper.io.lerped
}


/*
class MultiOmniphone(pcmDepth : Int, channelCount : Int) extends FixedLatencyPipeline {

  val io = new Bundle {
    val controls = Vec(slave Flow OmniphoneControls(), channelCount)
    val pcm = master Flow UInt(pcmDepth bits)
  }

  override val input = io.controls
  override val output = io.pcm


  val omniphones = Array.tabulate(channelCount) { i =>
    val channel = new OmniphoneChannel(pcmDepth, 96000)
    io.controls(i) >-> channel.io.controls
  }


  val mixer = new Area {



  }



}
*/


class OmniphoneChannel(pcmDepth : Int, sampleRate : Int) extends FixedLatencyPipeline[OmniphoneControls, SInt] {


  val io = new Bundle {
    val controls = slave Flow OmniphoneControls()
    val pcm = master Flow SInt(pcmDepth bits)
  }

  override val input = io.controls
  override val output = io.pcm





  // wave table indices per sample = frquency * wave table sample count / sample rate
  // 440 hz * 1024 / 96000 = 4 . 69
  // for 20,000 khz frequency the max indices per sample at this sampling rate is 213

  val curentWaveTablePosition = Reg( UInt(64 bits)) init 0

  val sampler = new WaveTableSampler(1024, pcmDepth)

  when(io.controls.fire) {
    curentWaveTablePosition := curentWaveTablePosition + (io.controls.wavetableIndicesPerSampleIntegerPart @@ io.controls.wavetableIndicesPerSampleFractionPart)
  }


  io.controls.map { in =>

    TupleBundle(curentWaveTablePosition, in.amplitude)

  } >=> sampler >~> { in =>
    val sample = in._1
    val amplitude = in._2

    val lerpArgs = Vec(U(0, 32 bits), sample, amplitude)

    TupleBundle(lerpArgs, amplitude)

  } >=> Lerper(32) >~> { out =>

    // uhh turn this into a signed integer somehow.

      val lerped = out._1
      val amplitude = out._2


      // i hope computer will optimize this
      val scaled = lerped + (0xFFFFFFFFL - amplitude) / 2
      val half = S(0x7FFFFFFFL, 33 bits)
      val signed = S(scaled, 33 bits)

    (signed - half).resize(32 bits)
  } >-> io.pcm


  val starting = Counter(4, True)
  val started = RegInit(False) setWhen starting.willOverflow

  if (GenerationFlags.simulation.isEnabled) {
    when(!started) {
      io.pcm.valid := False
      io.pcm.payload := 0xFFFF_FFFFL
      when(RegNext(io.pcm.payload === 0xFFFF_FFFFL) && !io.controls.valid) {
        io.pcm.payload := 0 // please god
      }
    }
  }

}


