package projectname

import projectname.LerpSim.DoubleToFraction
import projectname.OmniphoneSim.GetIndexRatesForFrequency
import projectname.XilinxPCIE_CQ_Descriptor.RequestType
import spinal.core._
import spinal.lib._

import scala.language.postfixOps


object XilinxPCIE_CQ_Descriptor{
  /*
  0000	Memory Read Request
  0001	Memory Write Request
  0010	I/O Read Request
  0011	I/O Write Request
  0100	Memory Fetch and Add Request
  0101	Memory Unconditional Swap Request
  0110	Memory Compare and Swap Request
  0111	Locked Read Request (allowed only in Legacy Devices)
  1000	Type 0 Configuration Read Request (on Requester side only)
  1001	Type 1 Configuration Read Request (on Requester side only)
  1010	Type 0 Configuration Write Request (on Requester side only)
  1011	Type 1 Configuration Write Request (on Requester side only)
  1100	Any message, except ATS and Vendor-Defined Messages
  1101	Vendor-Defined Message
  1110	ATS Message
  1111	Reserved
   */

  object RequestType extends Enumeration{
    type RequestType = Value
    val MemoryRead, MemoryWrite, IO_Read, IO_Write, MemoryFetchAdd, MemoryUnconditionSwap, MemoryCompareSwap, LockedRead,
    Type0ConfigRead, Type1ConfigRead, Type0ConfigWrite, Type1ConfigWrite, OtherMessage, VendorMessage, ATS_Message = Value
  }

}



case class XilinxPCIE_CQ_Descriptor() extends Bundle {

  // dword 0,1
  val addressType = Bits(2 bits)
  val address = UInt(62 bits) // this is a dword address!!

  // dword 2
  val dwordCount = UInt(11 bits)
  val requestType = Bits(4 bits)
  val reserved0 = Bits(1 bit)
  val deviceFunction = Bits(8 bits)
  val bus = Bits(8 bits)

  // dword 3
  val tag = UInt(8 bits)
  val targetFunction = UInt(8 bits)
  val barID = UInt(3 bits)
  val barAperture = UInt(6 bits)
  val transactionClass = Bits(3 bits)
  val attr = Bits(3 bits)
  val reserved1 = Bits(1 bit)
}


case class XilinxPCIE_CC_Descriptor() extends Bundle {

  // dword 0
  val lowerAddress = UInt(7 bits)
  val reserved0 = Bits(1 bit)
  val addressType = UInt(2 bits)
  val reserved1 = Bits(6 bits)
  val byteCount = UInt(13 bits)
  val locked = Bits(1 bit)
  val reserved2 = Bits(2 bits)

  // dword 1
  val dwordCount = UInt(11 bits)
  val completionStatus = Bits(3 bits)
  val poisonedCompletion = Bits(1 bit)
  val reserved3 = Bits(1 bit)
  val RequesterID_deviceFunction = Bits(8 bits)
  val RequesterID_bus = Bits(8 bits)

  // dword 2
  val tag = UInt(8 bits)
  val completerID_deviceFunction = UInt(8 bits)
  val completerID_bus = UInt(8 bits)
  val completerID_enable = Bits(1 bit)
  val transactionClass = Bits(3 bits)
  val attr = Bits(3 bits)
  val forceECRC = Bits(1 bits)
}



case class CompleterRequestBus() extends Bundle {

  val data =  Bits(256 bits)
  val user = Bits(88 bits)
  val keep = Bits(8 bits)
}


case class CompleterCompletionBus() extends Bundle {

  val data =  Bits(256 bits)
  val user = Bits(33 bits)
  val keep = Bits(8 bits)
}



class Receiver extends Component {

  val io = new Bundle {
      val completerRequests = slave Stream Fragment(CompleterRequestBus())
      val completerCompletions = master Stream Fragment(CompleterCompletionBus())
      val nonpostedRequestAllowed = out Bool()
      val nonpostedRequestCount = in UInt(6 bits) // hmm
  }

  val requestCount = Counter(32 bits, io.completerRequests.firstFire)
  val completionCount = Counter(32 bits, io.completerCompletions.firstFire)
  io.nonpostedRequestAllowed := True


  val cq_descriptor = io.completerRequests.data.as(XilinxPCIE_CQ_Descriptor())

  io.completerCompletions.payload := io.completerCompletions.getZero
  io.completerCompletions.valid := False

  val nonPostedRequest = Reg(XilinxPCIE_CQ_Descriptor())

  val mem = Mem(Bits(256 bits), 4096)
  val readAddr = UInt(12 bits); readAddr.assignDontCare()
  val readEnable = Bool()
  val readSize = Reg(UInt(4 bits))
  val readDataValid = RegNext(readEnable) init False
  readEnable := False

  val readData = mem.readSync(readAddr, readEnable)
  io.completerRequests.ready := True

  val omniphoneCount = 8


  val reset = RegInit(False).addAttribute("mark_debug")

  val controlsArea = new ResetArea(reset, true){
    val controlSets = Vec(Reg(OmniphoneControls()), omniphoneCount)
    val playControls = Vec(RegInit(False), omniphoneCount)
    controlSets.addAttribute("mark_debug")
    playControls.addAttribute("mark_debug")
  }

  import controlsArea._


  val readingFifo = Bool().addAttribute("mark_debug")
  readingFifo := False

  when(io.completerRequests.firstFire){

    // reading
    when(cq_descriptor.requestType === RequestType.MemoryRead.id){

      nonPostedRequest := cq_descriptor
      readEnable := True
      readAddr := cq_descriptor.address(0, 12 bits)
      readSize := cq_descriptor.dwordCount.resized

    // writing
    } elsewhen(cq_descriptor.requestType === RequestType.MemoryWrite.id) {

      val writeAddr = cq_descriptor.address(0, 12 bits)
      val dwords = io.completerRequests.data.subdivideIn(32 bits)
      val writeData = dwords(4).resized // 5th dword, the first four dwords are the descriptor


      for(i <- 0 until omniphoneCount){
        when(writeAddr === 0x300 + 0x10 * i + 0) {
          controlSets(i).amplitude := writeData.asUInt
        }

        when(writeAddr === 0x300 + 0x10 * i + 1) {
          controlSets(i).wavetableIndicesPerSampleIntegerPart := writeData.asUInt
        }

        when(writeAddr === 0x300 + 0x10 * i + 2) {
          controlSets(i).wavetableIndicesPerSampleFractionPart := writeData.asUInt
        }

        when(writeAddr === 0x300 + 0x10 * i + 3) {
          playControls(i) := writeData(0)
        }
      }



      when(writeAddr === 0x100) {
        reset := writeData(0)
        completionCount.clear()
        requestCount.clear()
      }


      mem.write(writeAddr, writeData.resize(256))
    }
  }


  val cc_descriptor = XilinxPCIE_CC_Descriptor()
  cc_descriptor.allowOverride()

  cc_descriptor := cc_descriptor.getZero

  cc_descriptor.lowerAddress := (nonPostedRequest.address << 2)(0, 7 bits)
  cc_descriptor.dwordCount := nonPostedRequest.dwordCount
  cc_descriptor.byteCount := nonPostedRequest.dwordCount << 2
  cc_descriptor.tag := nonPostedRequest.tag
  cc_descriptor.forceECRC := 1
  cc_descriptor.RequesterID_bus := nonPostedRequest.bus
  cc_descriptor.RequesterID_deviceFunction := nonPostedRequest.deviceFunction
  cc_descriptor.attr := nonPostedRequest.attr
  cc_descriptor.transactionClass := nonPostedRequest.transactionClass


  val dwordsPerBeat = 256 / 32 // 8?
  val dwordsForCompletionHeader = 3
  val dwordsLeftForPayloadOnFirstBeat = 5

  val singleBeatCompletion = readSize <= dwordsLeftForPayloadOnFirstBeat
  val secondBeatValid = RegNext(readDataValid && !singleBeatCompletion) init False
  val secondBeatData = Reg(Bits(96 bits)) // 3 dwords left over from a max of 8 dword read
  val secondBeatSize = RegNext(readSize - dwordsLeftForPayloadOnFirstBeat).resize(2)



  val resetArea = new ResetArea(reset, true){

    // figure out how to use the pipeline skidbuffer interface for this
    // unfortunately the extension i made only handles a single input and output
    // where this one has many inputs and a single output, but basically works as if all the inputs are or'd together
    // so we can still use the latency calculation for the pipeline

    val omniphone = new MultiOmniphone(32, 96000, omniphoneCount)
    omniphone.io.addAttribute("mark_debug")

    val omniphonePCM_fifo = StreamFifo(Bits(256 bits), 2)

    for(i <- 0 until omniphoneCount){
      omniphone.io.controls(i).payload := controlSets(i)
      omniphone.io.controls(i).valid := playControls(i) && omniphonePCM_fifo.io.push.ready
    }

    val skidBuffer = StreamFifo(omniphone.output.payload, omniphone.latency + 3)

    val overflow = Bool()
    val overflowed = RegInit(False) setWhen(overflow)
    overflowed.addAttribute("mark_debug")

    omniphone.io.pcm.toStream(overflow) >> skidBuffer.io.push

    omniphonePCM_fifo.io.pop.ready := False
    StreamWidthAdapter(skidBuffer.io.pop.map(_.asBits), omniphonePCM_fifo.io.push)

    val stop = !playControls.reduceBalancedTree( _ || _ )
    stop.addAttribute("mark_debug")
    omniphonePCM_fifo.io.flush := stop
    skidBuffer.io.flush := stop
  }
  import resetArea._



  val completionData = HandleRegisterAccess(RegNext(readAddr), readData, readDataValid)
  completionData.addAttribute("mark_debug")

  when(singleBeatCompletion && readDataValid) {
    // single beat completion

    io.completerCompletions.data(0, 96 bits) := cc_descriptor.asBits
    io.completerCompletions.data(96, 160 bits) := completionData.resized

    val keepBits = Bits(8 bits)
    val shifted = U(1) << (readSize + dwordsForCompletionHeader)
    keepBits := (shifted - 1).asBits.resized
    io.completerCompletions.keep := keepBits

    io.completerCompletions.valid := True
    io.completerCompletions.last := True

  } elsewhen(readDataValid && !secondBeatValid) {
    // first beat of two beat completion

    io.completerCompletions.data(0, 96 bits) := cc_descriptor.asBits
    io.completerCompletions.data(96, 160 bits) := completionData.resized
    io.completerCompletions.keep := 0xFF

    io.completerCompletions.valid := True
    io.completerCompletions.last := False

    secondBeatData := completionData(160, 96 bits)

    io.completerRequests.ready := False // we can't accept a request on this clock because it would overlap with the second beat of the previous request's completion

  } elsewhen(secondBeatValid) {

    io.completerCompletions.data(0, 96 bits) := secondBeatData
    io.completerCompletions.valid := True
    io.completerCompletions.last := True

    val keepBits = Bits(8 bits)
    val shifted = U(1) << secondBeatSize
    keepBits := (shifted - 1).asBits.resized
    io.completerCompletions.keep := keepBits

  }





  def HandleRegisterAccess(address: UInt, data: Bits, requestValid : Bool): Bits = {

    val ret = data.clone()
    ret := data

    when(address === 0x100) {
      ret := requestCount.value.asBits.resized
    }

    when(address === 0x101) {
      ret := completionCount.value.asBits.resized
    }

    when(address === 0x200) {
      ret := omniphonePCM_fifo.io.pop.payload

      when(requestValid){
        omniphonePCM_fifo.io.pop.ready := True
        readingFifo := True
      }
    }

    ret
  }


}





