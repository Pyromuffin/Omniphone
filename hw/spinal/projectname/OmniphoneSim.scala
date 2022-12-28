package projectname

import projectname.LerpSim.DoubleToFraction
import projectname.Omniphone.lerp
import spinal.core._
import spinal.core.sim._
import spinal.lib.sim._
import spinal.lib._

import scala.language.postfixOps

object LerpSim extends App {


  def DoubleToFraction( d : Double, depth : Int ) = {

    val largest = (1L << depth)
    (largest * d).toLong
  }




  def FractionToDouble(fraction : Long, depth : Int) = {

    val doubled = fraction * 1.0
    val largest = (1L << depth)

    doubled / largest

  }


  val depth = 32

  Config.sim.withFstWave.compile(new Lerper(depth)).doSim { dut =>
    // Fork a process to generate the reset and the clock on the dut
    dut.clockDomain.forkStimulus(period = 10)

   // val firstNumbers = Array(0, 100, 10)
   // val secondNumbers = Array(100, 200, 5)
   // val fractions = Array(0.5, 0.75, 0.5)

    val firstNumbers = Array.tabulate(10000) { i =>
      scala.util.Random.nextInt(1000000)
    }

    val secondNumbers = Array.tabulate(10000) { i =>
      scala.util.Random.nextInt(1000000)
    }

    val fractions = Array.tabulate(10000) { i =>
      scala.util.Random.nextDouble()
    }


    var pushIndex = 0

    FlowDriver(dut.io.numbers, dut.clockDomain) { n =>
      if(pushIndex < firstNumbers.length){
        n(0) #= firstNumbers(pushIndex)
        n(1) #= secondNumbers(pushIndex)
        n(2) #= DoubleToFraction(fractions(pushIndex), depth)

        pushIndex += 1

        true
      } else false

    }


    var errorSum = 0D
    var popIndex = 0
    FlowMonitor(dut.io.lerped, dut.clockDomain){ l =>

      val first = firstNumbers(popIndex)
      val second = secondNumbers(popIndex)
      val fraction = fractions(popIndex)

      val cooked = FractionToDouble( DoubleToFraction(fraction, depth), depth)
      val lerped = first * (1 - fraction) + second * fraction

      val error = scala.math.abs(l.toLong - lerped)
      errorSum += error

      if(error > 1) {
          println(s"$first lerped to $second by $fraction is ${l.toLong}, actual value is $lerped")
      }

      popIndex += 1
    }


    dut.clockDomain.waitSampling(100000)

    println("average error is " + errorSum / firstNumbers.length)

    val sampleRate = 96000

    val timeScaleDepth = 32
    val timePerPhaseUnit = 1.0 / (1L << timeScaleDepth)
    val timePerSample = 1.0 / sampleRate
    val phaseUnitsPerSample = timePerSample / timePerPhaseUnit

    println(s"time per phase unit $timePerPhaseUnit, time per sample $timePerSample, phase units per sample $phaseUnitsPerSample")

  }

}


object OmniphoneSim extends App {

  def GetIndexRatesForFrequency(frequency : Double, waveTableSamples : Int, sampleRate : Int ) = {

    // wave table indices per sample = frequency * wave table sample count / sample rate

    val rate = frequency * waveTableSamples / sampleRate
    val indexRate  = scala.math.floor(rate)
    val fractionRate = rate - indexRate


    (indexRate.toLong, DoubleToFraction(fractionRate, 32))
  }


  val frequency = 440
  val sampleRate = 96000

  class omniphone_tb extends Component {

    val io = new Bundle {
      val controls = slave Stream(OmniphoneControls())
      val pcm = master Stream(Bits(32 bits))
    }




    val omniphone = new Omniphone(32, 96000).streamWithSkidBuffer()
    val omniphonePCM_fifo = StreamFifo(Bits(32 bits), 100)
    omniphonePCM_fifo.io.push.simPublic()

    StreamWidthAdapter(omniphone.pop.stage.map(_.asBits), omniphonePCM_fifo.io.push)

    io.controls >> omniphone.push
    omniphonePCM_fifo.io.pop >> io.pcm
  }


  Config.sim.withFstWave.compile(new omniphone_tb ).doSim { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    val rates = GetIndexRatesForFrequency(frequency, 1024, sampleRate)

    dut.clockDomain.waitSampling()
    dut.io.controls.valid #= false


    dut.clockDomain.waitSampling(10)


    dut.io.controls.valid #= true
    dut.io.controls.wavetableIndicesPerSampleIntegerPart #= rates._1
    dut.io.controls.wavetableIndicesPerSampleFractionPart #= rates._2
    dut.io.controls.amplitude #= DoubleToFraction(0.1, 32)


    dut.io.pcm.ready #= false


    dut.clockDomain.waitSampling(100)


    dut.clockDomain.waitSamplingWhere(!dut.omniphonePCM_fifo.io.push.ready.toBoolean)

    val randomizer = StreamReadyRandomizer(dut.io.pcm, dut.clockDomain)
    //randomizer.factor = 0.1f

    /*
    var sampleIndex = 0

    StreamMonitor(dut.io.pcm, dut.clockDomain) { out =>
      assert(out.toLong == sampleIndex)
      sampleIndex += 1
    }
    */

    dut.clockDomain.waitSampling(100000)
  }

}