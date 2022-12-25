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

    ret
  }
}

// i dont actually know what frequency this is running at???

case class OmniphoneControls() extends Bundle {

  val wavetableIndicesPerSampleIntegerPart = UInt(32 bits)
  val wavetableIndicesPerSampleFractionPart = UInt(32 bits)


  val amplitude = UInt(32 bits)
  val play = Bool()

}

case class OmniphoneWaveTableUpdate(pcmDepth : Int) extends Bundle {
  val pcm = UInt(pcmDepth bits)
  val index = UInt(10 bits)
}


class Lerper(depth : Int) extends Component {

  val io = new Bundle {
    val numbers = slave Flow Vec(UInt(depth bits), 3)
    val lerped = master Flow UInt(depth bits)
  }

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


class WaveTableSampler(waveTableSampleCount : Int, pcmDepth : Int) extends Component {


  def SampleWaveTable(table: Mem[UInt], index: UInt, fraction: UInt) = new Area {

    val firstSample = table.readAsync(index.resize(10 bits))
    val secondSample = table.readAsync(index.resize(10 bits) + 1) // should wrap if length is always a power of two

    val fractionSizeDifference = firstSample.getBitsWidth - fraction.getBitsWidth
    val resizedFraction = fraction @@ U(0, fractionSizeDifference bits)

    val lerpArea = lerp(firstSample, secondSample, resizedFraction)
    val ret = lerpArea.ret
  }


  val io = new Bundle {
    val uv = slave Flow UInt(64 bits)
    val sample = master Flow UInt(pcmDepth bits)

  }


  // stores one cycle? maybe it could store half a cycle if we assume they're symmetric
  val sinTable = CreateSineWaveTable(pcmDepth, waveTableSampleCount)
  val waveTable = Mem(UInt(pcmDepth bits), 1024) init sinTable.map(U(_, pcmDepth bits))

  val integerPart = io.uv.payload(32, 32 bits).resize( log2Up(waveTableSampleCount) bits)
  val fractionPart = io.uv.payload(0, 32 bits)

  val firstSample = waveTable.readSync(integerPart, io.uv.fire)
  val secondSample = waveTable.readSync(integerPart + 1, io.uv.fire)

  val samplesValid = RegNext(io.uv.fire)

  val lerper = new Lerper(pcmDepth)

  lerper.io.numbers.payload(0) := firstSample
  lerper.io.numbers.payload(1) := secondSample
  lerper.io.numbers.payload(2) := RegNext(fractionPart)
  lerper.io.numbers.valid := samplesValid

  io.sample <-< lerper.io.lerped
}



class Omniphone(pcmDepth : Int, sampleRate : Int) extends Component {


  val io = new Bundle {
    val controls = slave Flow OmniphoneControls()
    val pcm = master Stream UInt(pcmDepth bits)
  }


  if(GenerationFlags.simulation.isEnabled) {
    io.pcm.valid := False
    io.pcm.payload := 0xFFFF_FFFFL
    when(RegNext(io.pcm.payload === 0xFFFF_FFFFL) && !io.controls.valid) {
      io.pcm.payload := 0 // please god
    }
  }


  val controls = io.controls.toReg init io.controls.payload.getZero
  val timeScaleDepth = 32
  val timePerPhaseUnit = 1.0 / (1L << timeScaleDepth)
  val timePerSample = 1.0 / sampleRate
  val phaseUnitsPerSample = timePerSample / timePerPhaseUnit // 44739 at 96000 khz

  println(s"time per phase unit $timePerPhaseUnit, time per sample $timePerSample, phase units per sample $phaseUnitsPerSample")

  val sampleIndex = Counter(32 bits, io.pcm.fire)

  // wave table indices per sample = frquency * wave table sample count / sample rate
  // 440 hz * 1024 / 96000 = 4 . 69
  // for 20,000 khz frequency the max indices per sample at this sampling rate is 213

  val curentWaveTablePosition = Reg( UInt(64 bits)) init 0
  val integer = curentWaveTablePosition(32, 32 bits)
  val fraction = curentWaveTablePosition(0, 32 bits)


  val sampler = new WaveTableSampler(1024, pcmDepth)
  sampler.io.uv.payload := curentWaveTablePosition
  sampler.io.uv.valid := controls.play && io.pcm.ready

  sampler.io.sample.toStream >-> io.pcm

  when(sampler.io.uv.fire){
    curentWaveTablePosition := curentWaveTablePosition + (controls.wavetableIndicesPerSampleIntegerPart @@ controls.wavetableIndicesPerSampleFractionPart)
  }

  /*
  val pcm = SampleWaveTable(waveTable, integer, fraction)

  when(controls.play && io.pcm.ready){

    curentWaveTablePosition := curentWaveTablePosition + (controls.wavetableIndicesPerSampleIntegerPart @@ controls.wavetableIndicesPerSampleFractionPart)

    // now scale by amplitude which will be a fraction
    val scaled = lerp(U(0, pcmDepth bits), pcm.ret, controls.amplitude).ret + (0xFFFFFFFFL -  controls.amplitude) / 2

    io.pcm.valid := True
    io.pcm.payload := scaled
  }
*/

}


