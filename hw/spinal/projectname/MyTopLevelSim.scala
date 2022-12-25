package projectname

import spinal.core._
import spinal.core.sim._
import spinal.lib.sim.{StreamDriver, StreamMonitor}


object MyTopLevelSim extends App {
  Config.sim.withFstWave.compile(new Receiver()).doSim { dut =>
    // Fork a process to generate the reset and the clock on the dut
    dut.clockDomain.forkStimulus(period = 10)


    val firstRequest = BigInt("00000000000000000000000000000000107000000000000100000078060fc000", 16)
    val secondRequest =  BigInt("00000000000000000000000000000000107000010000000100000078060fc404", 16)
    val thirdRequest =   BigInt("00000000000000000000000000069420107000000000080100000078060fc004", 16)
    val fourthRequest = BigInt("00000000000000000000000000000000107000020000000100000078060fc004", 16)


    val requests = Array(firstRequest, secondRequest, thirdRequest, fourthRequest)

    var index = 0

    val streamDriver = StreamDriver(dut.io.completerRequests, dut.clockDomain){ d =>

      if(index < requests.length){
        d.last #= true
        d.data #= requests(index)
        index += 1
        d.user #= 0

        true
      } else
        false

    }


    val streamMonitor = StreamMonitor(dut.io.completerCompletions, dut.clockDomain) { d =>
      val bytes = d.data.toBytes
      val payload = bytes.slice(12, 16).reverse
      val number = BigInt(payload).toLong.toHexString

      println("got completion with data " + number)

    }

    dut.io.completerCompletions.ready #= true


    dut.clockDomain.waitSampling(1000)
  }
}




object AddressAligned extends App {
  Config.sim.withFstWave.compile(new Receiver()).doSim { dut =>
    // Fork a process to generate the reset and the clock on the dut
    dut.clockDomain.forkStimulus(period = 10)


    val firstRequest = BigInt("00000000000000000000000000000000107000000000000100000078060fc000", 16)
    val secondRequest =  BigInt("00000000000000000000000000000000107000010000000100000078060fc004", 16)
    val thirdRequest =   BigInt("00000000000000000000000000069420107000000000080100000078060fc004", 16)
    val thirdData =   BigInt("0000000000000000000000000000000000000000000000000000000000069420", 16)
    val fourthRequest = BigInt("00000000000000000000000000000000107000020000000100000078060fc004", 16)


    val requests = Array(firstRequest, secondRequest, thirdRequest, thirdData, fourthRequest)

    var index = 0

    val streamDriver = StreamDriver(dut.io.completerRequests, dut.clockDomain){ d =>

      if(index < requests.length){
        d.last #= true
        d.data #= requests(index)
        index += 1
        d.user #= 0

        true
      } else
        false

    }


    val streamMonitor = StreamMonitor(dut.io.completerCompletions, dut.clockDomain) { d =>
      val bytes = d.data.toBytes
      val payload = bytes.slice(12, 16).reverse
      val number = BigInt(payload).toLong.toHexString

      println("got completion with data " + number)

    }

    dut.io.completerCompletions.ready #= true


    dut.clockDomain.waitSampling(1000)
  }
}
