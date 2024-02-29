package aib3d

import scala.math.{min, max}
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods.{pretty, render}
import doodle.core._
import doodle.core.format._
import doodle.syntax.all._
import doodle.java2d._
import cats.effect.unsafe.implicits.global

import chisel3._

import chisel3.experimental.{BaseModule, DataMirror}
import org.chipsalliance.cde.config.Parameters

import aib3d.io._

/** Generate bump map collateral */
object GenCollateral {
  /** Generates a JSON file with the IO cell instance name and location,
    * core pin name and location, and other physical design constraints.
    */
  def toJSON(iocells: Seq[BaseModule with IOCellConnects])
    (implicit params: AIB3DParams): String = {
    pretty(render(("bump map" -> params.flatBumpMap.map{ b =>
      val io = iocells.find(_.forBump == b)
      ("bump_name" -> b.bumpName) ~
      ("core_sig" -> (if (b.coreSig.isDefined) Some(b.coreSig.get.fullName) else None)) ~
      ("iocell_path" -> (if (io.isDefined) Some(io.get.pathName) else None)) ~
      ("bump_x" -> b.location.get.x) ~
      ("bump_y" -> b.location.get.y) ~
      ("pin_x" -> (if (b.coreSig.isDefined) Some(b.coreSig.get.pinLocation.get.x) else None)) ~
      ("pin_y" -> (if (b.coreSig.isDefined) Some(b.coreSig.get.pinLocation.get.y) else None)) ~
      ("mod_idx" -> (if (b.modIdx.isDefined) Some(b.modIdx.get.linearIdx) else None))
    })))
  }

  /** Generates a JSON file consumable by the Hammer VLSI flow tool.
    * This is a different from the JSON file generated by toJSON.
    */
  def toHammerJSON(iocells: Seq[BaseModule with IOCellConnects])
    // TODO: support mismatched pitchH/pitchV (not yet supported by Hammer) - need gcd of them
    // TODO: TSV constraints
    (implicit params: AIB3DParams): String = {
    // Floating point precision
    def roundToNm(x: Double): Double = (x * 1000).round / 1000.0
    // Redundancy
    val codeRed = params.gp.redundArch == 1
    val shiftRed = params.gp.redundArch == 2
    val redModName = if (codeRed) "coding" else if (shiftRed) "shifting" else ""

    // Bumps
    val bumps =
      ("vlsi.inputs.bumps_mode" -> "manual") ~
      ("vlsi.inputs.bumps" ->
        ("x" -> params.bumpMap(0).length) ~
        ("y" -> params.bumpMap.length) ~
        ("pitch" -> params.gp.pitch) ~
        ("global_x_offset" -> (
          if (params.pinSide == "W") params.ip.bumpOffset else 0.0)) ~
        ("global_y_offset" -> (
          if (params.pinSide == "S") params.ip.bumpOffset else 0.0)) ~
        ("cell" -> params.ip.bumpCellName) ~
        ("assignments" -> params.flatBumpMap.map( b =>
          ("name" -> b.bumpName) ~
          // location is integer multiple of pitch, 1-indexed
          ("x" -> roundToNm(b.location.get.x / params.gp.pitch + 0.5).toInt) ~
          ("y" -> roundToNm(b.location.get.y / params.gp.pitch + 0.5).toInt)
        ))
      )

    // Pins
    val coreSigs = iocells.withFilter(_.forBump.coreSig.isDefined).map(_.forBump.coreSig.get)
    val sideMap = Map("N" -> "top", "S" -> "bottom", "E" -> "right", "W" -> "left")
    val pins =
      ("vlsi.inputs.pin_mode" -> "generated") ~
      ("vlsi.inputs.pin" ->
        ("generate_mode" -> "semi_auto") ~
        ("assignments" -> (coreSigs.map( c =>
          ("pins" -> ((if (c.name.contains("CKP")) "clocks_" else "") + c.fullName)) ~
          ("side" -> sideMap(params.pinSide)) ~
          ("layers" -> Seq(c.pinLayer.get)) ~
          ("location" -> Seq(roundToNm(c.pinLocation.get.x),
                             roundToNm(c.pinLocation.get.y)))
        ) ++ Seq(  // TODO: constrain within the edge
          ("pins" -> "{clock reset auto* ioCtrl *Faulty}") ~
          ("side" -> sideMap(params.pinSide)) ~
          ("layers" -> params.ip.layerPitch.keys)
        )))
      )

    // Placements
    val topWidth = params.bumpMap(0).length * params.gp.pitchH +
      (if (!params.isWide) params.ip.bumpOffset else 0.0)
    val topHeight = params.bumpMap.length * params.gp.pitchV +
      (if (params.isWide) params.ip.bumpOffset else 0.0)
    val placeKOZ = max(params.ip.bprKOZRatio.getOrElse(0.0), params.ip.tsvKOZRatio.getOrElse(0.0))
    val places =
      ("vlsi.inputs.placement_constraints" -> (Seq(
        ("path" -> "Patch") ~
        ("type" -> "toplevel") ~
        ("x" -> 0) ~
        ("y" -> 0) ~
        ("width" -> roundToNm(topWidth)) ~
        ("height" -> roundToNm(topHeight)) ~
        ("margins" ->
          ("left" -> 0) ~
          ("right" -> 0) ~
          ("top" -> 0) ~
          ("bottom" -> 0))
      ) ++ iocells.map( i =>  // IO cell placement
        // Replace Target delimiters with / for P&R
        // Fields depend on whether we are using blackboxes or models
        // TODO: breaks if IO cell beneath top hierarchy
        ("path" -> i.pathName.replace(".","/")) ~
        ("type" -> (if (params.ip.blackBoxModels) "placement" else "hardmacro")) ~
        ("x" -> roundToNm(i.forBump.location.get.x - (
          if (params.ip.blackBoxModels) params.gp.pitchH / 2 else 0.0))) ~
        ("y" -> roundToNm(i.forBump.location.get.y - (
          if (params.ip.blackBoxModels) params.gp.pitchV / 2 else 0.0))) ~
        ("width" -> (if (params.ip.blackBoxModels) Some(params.gp.pitchH)
                     else None)) ~
        ("height" -> (if (params.ip.blackBoxModels) Some(params.gp.pitchV)
                      else None)) ~
        ("master" -> (if (params.ip.blackBoxModels) None
                      else Some(i.desiredName)))
        // TODO: top layer for halos
      //) ++ params.flatBumpMap.map( b =>  // Routing KOZ
      //  ("path" -> s"Patch/${b.bumpName}_route_koz") ~
      //  ("type" -> "obstruction") ~
      //  ("obs_types" -> Seq("route", "power")) ~
      //  ("x" -> roundToNm(b.location.get.x - params.ip.viaKOZRatio * params.gp.pitchH / 2)) ~
      //  ("y" -> roundToNm(b.location.get.y - params.ip.viaKOZRatio * params.gp.pitchV / 2)) ~
      //  ("width" -> roundToNm(params.ip.viaKOZRatio * params.gp.pitchH)) ~
      //  ("height" -> roundToNm(params.ip.viaKOZRatio * params.gp.pitchV))
      ) ++ (if (placeKOZ > 0) params.flatBumpMap.map( b =>  // Place KOZ
        ("path" -> s"Patch/${b.bumpName}_place_koz") ~
        ("type" -> "obstruction") ~
        ("obs_types" -> Seq("place")) ~
        ("x" -> roundToNm(b.location.get.x - placeKOZ * params.gp.pitchH / 2)) ~
        ("y" -> roundToNm(b.location.get.y - placeKOZ * params.gp.pitchV / 2)) ~
        ("width" -> roundToNm(placeKOZ * params.gp.pitchH)) ~
        ("height" -> roundToNm(placeKOZ * params.gp.pitchV))
      ) else Seq.empty))) ~
      ("vlsi.inputs.placement_constraints_meta" -> "append")

    // SDC: clocks, delays, loads
    // Note 1: directions seem reversed (derived from bumps, not facing the core)
    // Note 2: it is assumed clk tree delay is 2/3 of clk in to data out delay
    // TODO: this depends on the voltage-delay curve of the spec
    val tPeriod = 0.25  // 4 GT/s
    val spread = 0.2 * tPeriod  // 20% UI spread between corners
    val tdMin = 0.1 * tPeriod  // min for Dtx & Drx
    val tdMax = tdMin + spread  // max for Dtx & Drx
    //val tclkMax = 0.16 * tPeriod  // Core <-> bump delay. 0.32 UI split b/w Tx & Rx
    //val tclkMin = 0.06 * tPeriod // Needed for synthesis
    val tSetupHold = 0.01  // Conservative estimate of setup/hold time
    val tj = 0.05 * tPeriod  // Manifested Tx/Rx clk differential jitter
    val psmm = 300  // pS/mm estimate
    // txClocks returns (bump name, core path, muxed clock name)
    val txClocks = coreSigs.collect{ case c if (
      DataMirror.checkTypeEquivalence(c.ioType, Clock()) &&
      DataMirror.specifiedDirectionOf(c.ioType) == SpecifiedDirection.Output)
      => (c.fullName, "clocks_" + c.fullName, c.muxedClk(offset=params.redMods))}
    // TODO: don't match by name
    // rxClocks returns (bump name, core path, iocell output path)
    val rxClocks = iocells.filter(_.forBump.bumpName.contains("RXCKP")).map(i =>
      (i.forBump.bumpName, "clocks_" + i.forBump.bumpName, i.pathName.replace(".","/") + "/io_rxClk"))
    // rxClocksCoded should be empty if no coding
    val rxClocksCoded = iocells.filter(_.forBump.bumpName.contains("RXCKR")).map(i =>
      (i.forBump.bumpName, "clocks_" + i.forBump.bumpName, i.pathName.replace(".","/") + "/io_rxClk"))
    val rxClocksForMuxing =
      if (params.isWide)  // transpose to get in ascending order
        rxClocks.grouped(params.modColsWR).toSeq.transpose.flatten
      else
        rxClocks
    val coreTxs = coreSigs.filter(c =>
      DataMirror.specifiedDirectionOf(c.ioType) == SpecifiedDirection.Output &&
      !DataMirror.checkTypeEquivalence(c.ioType, Clock())
    )
    val coreRxs = coreSigs.filter(c =>
      DataMirror.specifiedDirectionOf(c.ioType) == SpecifiedDirection.Input &&
      !DataMirror.checkTypeEquivalence(c.ioType, Clock())
    )
    val bumpTxs = params.flatBumpMap.collect{case b:TxSig => b}
    val bumpRxs = params.flatBumpMap.collect{case b:RxSig => b}
    val bits = coreTxs.length + coreRxs.length
    val sdc =
      ("vlsi.inputs.clocks" -> (Seq(
        ("name" -> "clock") ~
        ("period" -> "1 ns") ~
        ("uncertainty" -> "0.02 ns")
      ) ++ txClocks.map(c =>
        ("name" -> c._1) ~
        ("path" -> c._2) ~
        ("period" -> "250 ps") ~
        ("uncertainty" -> f"${tj}%.4f ns") ~
        ("group" -> c._1)
      ) ++ txClocks.zipWithIndex.map{ case(c, i) =>  // clocks to Tx IO cells
        ("name" -> s"io_${c._1}") ~
        ("path" -> s"[get_pins */clksToTx_$i]") ~
        ("generated" -> true) ~
        ("divisor" -> 1) ~
        ("source_path" -> c._2) ~
        ("uncertainty" -> f"${tj}%.4f ns") ~
        ("group" -> c._1)
        // Tx direct clocks
      } ++ txClocks.map(c =>
        ("name" -> s"out_${c._1}") ~
        ("path" -> s"[get_ports ${c._1}]") ~
        ("generated" -> true) ~
        ("divisor" -> 1) ~
        ("source_path" -> c._2) ~
        ("uncertainty" -> f"${tj}%.4f ns") ~
        ("group" -> c._1)
      ) ++ txClocks.map(c =>
        ("name" -> s"out_${c._1.replace("CKP","CKR")}") ~
        ("path" -> s"[get_ports ${c._1.replace("CKP","CKR")}]") ~
        ("generated" -> true) ~
        ("divisor" -> 1) ~
        ("source_path" -> c._2) ~
        ("uncertainty" -> f"${tj}%.4f ns") ~
        ("group" -> c._1)
      ) ++ rxClocks.map(c =>
        ("name" -> s"invert_${c._1}") ~
        ("path" -> c._1) ~
        ("period" -> "250 ps") ~
        ("uncertainty" -> f"${tj}%.4f ns") ~
        ("group" -> c._1)
      ) ++ rxClocks.map(c =>
        ("name" -> c._1) ~
        ("path" -> s"hpin:${c._3}") ~
        ("generated" -> true) ~
        ("divisor" -> -1) ~
        ("source_path" -> c._1) ~
        ("uncertainty" -> f"${tj}%.4f ns") ~
        ("group" -> c._1)
      ) ++ rxClocksCoded.map(c =>
        ("name" -> s"invert_${c._1}") ~
        ("path" -> c._1) ~
        ("period" -> "250 ps") ~
        ("uncertainty" -> f"${tj}%.4f ns") ~
        ("group" -> s"${c._1.replace("RXCKR", "RXCKP")}")
      ) ++ rxClocksCoded.map(c =>
        ("name" -> c._1) ~
        ("path" -> s"hpin:${c._3}") ~
        ("generated" -> true) ~
        ("divisor" -> -1) ~
        ("source_path" -> c._1) ~
        ("uncertainty" -> f"${tj}%.4f ns") ~
        ("group" -> s"${c._1.replace("RXCKR", "RXCKP")}")
      ) ++ rxClocks.zipWithIndex.map{ case (c, i) =>  // clocks to Rx IO cells
        ("name" -> s"io_${c._1}") ~
        ("path" -> s"[get_pins */clksToRx_$i]") ~
        ("generated" -> true) ~
        ("divisor" -> 1) ~
        ("source_path" -> s"hpin:${c._3}") ~
        ("uncertainty" -> f"${tj}%.4f ns") ~
        ("group" -> c._1)
      } ++ rxClocks.map(c =>  // Rx direct clocks
        ("name" -> s"out_${c._1}") ~
        ("path" -> s"[get_ports clocks_${c._1}]") ~
        ("generated" -> true) ~
        ("divisor" -> 1) ~
        ("source_path" -> s"hpin:${c._3}") ~
        ("uncertainty" -> f"${tj}%.4f ns") ~
        ("group" -> c._1)
      ))) ~
      // Core-side signal delays relative to core-facing clocks
      // Must be written out to final LIB if standalone
      ("vlsi.inputs.delays" -> (coreTxs.map(c =>
        ("name" -> c.fullName) ~
        ("clock" -> c.relatedClk.get) ~
        ("direction" -> "input") ~
        //("delay" -> f"${(tdMax + tclkMax) * 2/3 - tSetupHold}%.4f ns") ~
        ("delay" -> f"${4 * tdMin + spread}%.4f ns") ~
        ("corner" -> "setup")
      ) ++ coreTxs.map(c =>
        ("name" -> c.fullName) ~
        ("clock" -> c.relatedClk.get) ~
        ("direction" -> "input") ~
        //("delay" -> f"${(tdMin + tclkMin) * 2/3 + tSetupHold}%.4f ns") ~
        ("delay" -> f"${4 * tdMin}%.4f ns") ~
        ("corner" -> "hold")
      ) ++ coreRxs.map(c =>
        ("name" -> c.fullName) ~
        ("clock" -> s"out_${c.relatedClk.get}") ~
        ("direction" -> "output") ~
        ("delay" -> f"${tPeriod/2}%.4f ns") ~  // half period budget
        ("corner" -> "setup")
      ) ++ coreRxs.map(c =>
        ("name" -> c.fullName) ~
        ("clock" -> s"out_${c.relatedClk.get}") ~
        ("direction" -> "output") ~
        ("delay" -> f"0 ns") ~  // no hold time budget
        ("corner" -> "hold")
//      ) ++ bumpTxs.map(b =>
//        ("name" -> b.bumpName) ~
//        ("clock" -> b.relatedClk.get) ~
//        ("direction" -> "output") ~
//        ("delay" -> f"${tdMax + tclkMax}%.4f ns") ~
//        ("corner" -> "setup")
//      ) ++ bumpTxs.map(b =>
//        ("name" -> b.bumpName) ~
//        ("clock" -> b.relatedClk.get) ~
//        ("direction" -> "output") ~
//        ("delay" -> f"${tdMin + tclkMin}%.4f ns") ~
//        ("corner" -> "hold")
      ) ++ bumpRxs.map(b =>
        ("name" -> b.bumpName) ~
        //("clock" -> b.relatedClk.get) ~
        ("clock" -> s"invert_${b.relatedClk.get}") ~
        ("direction" -> "input") ~
        //("delay" -> f"${tdMax - tPeriod/2}%.4f ns") ~
        ("delay" -> f"${tdMax}%.4f ns") ~
        ("corner" -> "setup")
      ) ++ bumpRxs.map(b =>
        ("name" -> b.bumpName) ~
        //("clock" -> b.relatedClk.get) ~
        ("clock" -> s"invert_${b.relatedClk.get.replace("CKP","CKR")}") ~
        ("direction" -> "input") ~
        //("delay" -> f"${tdMax - tPeriod/2}%.4f ns") ~
        ("delay" -> f"${tdMax}%.4f ns") ~
        ("corner" -> "setup")
      ) ++ bumpRxs.map(b =>
        ("name" -> b.bumpName) ~
        //("clock" -> b.relatedClk.get) ~
        ("clock" -> s"invert_${b.relatedClk.get}") ~
        ("direction" -> "input") ~
        //("delay" -> f"${tdMin - tPeriod/2}%.4f ns") ~
        ("delay" -> f"${tdMin}%.4f ns") ~
        ("corner" -> "hold")
      ) ++ bumpRxs.map(b =>
        ("name" -> b.bumpName) ~
        //("clock" -> b.relatedClk.get) ~
        ("clock" -> s"invert_${b.relatedClk.get.replace("CKP","CKR")}") ~
        ("direction" -> "input") ~
        //("delay" -> f"${tdMin - tPeriod/2}%.4f ns") ~
        ("delay" -> f"${tdMin}%.4f ns") ~
        ("corner" -> "hold")
      ))) ~
      // TODO: parameterize default loading based on technology
      ("vlsi.inputs.default_output_load" -> "5 fF") ~
      ("vlsi.inputs.output_loads" -> (Seq(
        // TODO: parameterize pad cap
        ("name" -> "[get_ports \"TXDATA* TXCK* TXRED*\"]") ~
        ("load" -> "40 fF")
      ))) ~
      ("vlsi.inputs.custom_sdc_constraints" -> (Seq(
        // Global constraints
        // These are static bits
        "set_false_path -from *ioCtrl*",
        "set_false_path -from *faulty*",  // OK even if no redundancy
        "set_false_path -from *dbi*",  // OK even if no redundancy
        // Max transition for entire design. Assumes Lib units in ns.
        // TODO: determine power/jitter tradeoff of transition
        "set_max_transition 0.05 [current_design]",
        // Max dynamic power for the entire design. Not supported by all tools.
        f"set_max_dynamic_power [expr 0.05 * $bits / 0.25]mW",
        // set_clock_latency models ideal clock tree delay during synthesis
        // set_ideal_latency models ideal clock pass-thru delay during synthesis
        // TODO: set_ideal_latency doesn't seem to have any effect unless network is set to ideal (incorrect)
        // TODO: this depends on the bump pitch/number of clock sinks
        f"set_clock_latency -min ${tdMin * 2/3}%.4f [get_clocks io_TXCK*]",
        f"set_clock_latency -max ${tdMax * 2/3}%.4f [get_clocks io_TXCK*]",
        //f"set_ideal_latency -min ${tclkMin}%.4f [get_ports TXCK*]",
        //f"set_ideal_latency -max ${tclkMax}%.4f [get_ports TXCK*]",
        f"set_clock_latency -min ${tdMin * 2/3}%.4f [get_clocks io_RXCK*]",
        f"set_clock_latency -max ${tdMax * 2/3}%.4f [get_clocks io_RXCK*]",
        //f"set_ideal_latency -min ${tclkMin}%.4f [get_ports clocks_RXCK*]",
        //f"set_ideal_latency -max ${tclkMax}%.4f [get_ports clocks_RXCK*]",
        "set_propagated_clock [all_clocks]",  // Use calculated clock network latency for timing analysis in P&R
        // Set transition times on bumps
        // TODO: should this be set_drive in combo with set_load? Only works if bidir?
        f"set_min_transition ${tPeriod/10}%.4f [get_ports \"TXDATA* TXCK*\"]",
        f"set_max_transition ${tPeriod/6}%.4f [get_ports \"TXDATA* TXCK*\"]",
        f"set_input_transition -min ${tPeriod/10}%.4f [get_ports \"RXDATA* RXCK*\"]",
        f"set_input_transition -max ${tPeriod/6}%.4f [get_ports \"RXDATA* RXCK*\"]",
        // Determines data-data skew (1st order). TODO: Add > 0.02UI margin?
        // Note: may not be supported by all tools (set in CTS options instead)
        // TODO: In synthesis, set_clock_skew is modeled as uncertainty. But we don't want this in P&R.
        //f"set_clock_skew ${0.1 * tPeriod}%.4f [all_clocks]",
        // Max capacitance for core-facing outputs (assumes Lib units in pF).
        "set_max_capacitance 0.01 [get_ports clocks_RXCKP*]"  // clocks
      ) ++ coreRxs.map(c => s"set_max_capacitance 0.01 [get_ports ${c.fullName}]"  // data
        // Tx data delay relative to Tx output clock
        // For setup, output delay adjusts (earlier) the capturing edge (1 period away)
        // So we need to set the max delay to 1 period - tdMax for setup
        // Clock uncertainty should not be factored into this calculation - subtract it
      ) ++ bumpTxs.flatMap(b => Seq(
        //f"set_output_delay ${tPeriod - tdMax - tj}%.4f -clock [get_clocks ${b.relatedClk.get}] -max [get_ports ${b.bumpName}] -reference_pin [get_ports ${b.relatedClk.get}]",  // setup
        //f"set_output_delay ${-(tdMin - tj)}%.4f -clock [get_clocks ${b.relatedClk.get}] -min [get_ports ${b.bumpName}] -reference_pin [get_ports ${b.relatedClk.get}]"  // hold
        f"set_output_delay ${tPeriod - tdMax - tj}%.4f -clock [get_clocks out_${b.relatedClk.get}] -max [get_ports ${b.bumpName}]",  // setup
        f"set_output_delay ${tPeriod - tdMax - tj}%.4f -clock [get_clocks out_${b.relatedClk.get.replace("CKP","CKR")}] -max [get_ports ${b.bumpName}]",  // setup
        f"set_output_delay ${-(tdMin - tj)}%.4f -clock [get_clocks out_${b.relatedClk.get}] -min [get_ports ${b.bumpName}]",  // hold
        f"set_output_delay ${-(tdMin - tj)}%.4f -clock [get_clocks out_${b.relatedClk.get.replace("CKP","CKR")}] -min [get_ports ${b.bumpName}]"  // hold
        // Tx & Rx data-to-data skew
      //)) ++ bumpTxs.groupBy(_.relatedClk.get).map{ case(clk, ports) =>
      //  val plist = ports.map(b => s"port:${b.bumpName}").mkString(" ")
      //  f"set_data_check ${0.12 * tPeriod}%.4f -from {$plist} -to {$plist}"
      //  // Rx data-to-data skew
      //} ++ coreRxs.groupBy(_.relatedClk.get).map{ case(clk, ports) =>
      //  val plist = ports.map(c => s"port:${c.fullName}").mkString(" ")
      //  f"set_data_check ${0.12 * tPeriod}%.4f -from {$plist} -to {$plist}"
      //)) ++ txClocks.zipWithIndex.map{ case(c, i) =>  // preserve the generated Tx clock networks in synthesis
      //  s"set_dont_touch_network [get_clocks ${c._1}]"
      //} ++ rxClocks.zipWithIndex.map{ case(c, i) =>  // preserve the generated Rx clock networks in synthesis
      //  s"set_dont_touch_network [get_clocks ${c._1}]"
      //} ++ rxClocksCoded.zipWithIndex.map{ case(c, i) =>  // preserve the generated Rx clock networks in synthesis
      //  s"set_dont_touch_network [get_clocks ${c._1}]"
      //} ++ txClocks.flatMap(c => Seq(  // direct Tx clocks delay
      //  f"set_max_delay -from [get_ports ${c._2}] -to [get_ports ${c._1}] ${tclkMax}%.4f",
      //  f"set_min_delay -from [get_ports ${c._2}] -to [get_ports ${c._1}] ${tclkMin}%.4f"
      //)) ++ (if (codeRed) txClocks.flatMap(c => Seq(  // redundant Tx clocks delay
      //  f"set_max_delay -from [get_ports ${c._2}] -to [get_ports ${c._1.replace("CKP", "CKR")}] ${tclkMax}%.4f",
      //  f"set_min_delay -from [get_ports ${c._2}] -to [get_ports ${c._1.replace("CKP", "CKR")}] ${tclkMin}%.4f"
      //  )) else if (shiftRed) txClocks.flatMap(c => Seq(  // muxed Tx clocks delay
      //  f"set_max_delay -from [get_ports ${c._2}] -to [get_ports ${c._3}] ${tclkMax}%.4f",
      //  f"set_min_delay -from [get_ports ${c._2}] -to [get_ports ${c._3}] ${tclkMin}%.4f"
      //  )) else Seq.empty
      //) ++ (if (shiftRed) rxClocksForMuxing.dropRight(2) else rxClocksForMuxing).flatMap(c => Seq(  // direct Rx clocks delay
      //  f"set_min_delay -from hpin:${c._3} -to [get_ports ${c._2}] ${tclkMin}%.4f",
      //  f"set_max_delay -from hpin:${c._3} -to [get_ports ${c._2}] ${tclkMax}%.4f"
      //)) ++ (if (shiftRed) (rxClocksForMuxing.drop(2) zip rxClocksForMuxing.dropRight(2)).flatMap{ case (c1, c2) => Seq(  // muxed Rx clocks delay
      //  f"set_min_delay -from hpin:${c1._3} -to [get_ports ${c2._2}] ${tclkMin}%.4f",
      //  f"set_max_delay -from hpin:${c1._3} -to [get_ports ${c2._2}] ${tclkMax}%.4f"
      //  )} else Seq.empty
      )) ++ rxClocksCoded.flatMap(c => Seq(  // clock exclusivity for coded Rx clocks
        s"set_false_path -from [get_clocks ${c._1}] -to [get_clocks ${c._1.replace("RXCKR", "RXCKP")}]",
        s"set_false_path -from [get_clocks ${c._1.replace("RXCKR", "RXCKP")}] -to [get_clocks ${c._1}]"
      ))))

    // Power intent
    val power =
      ("vlsi.inputs.power_spec_mode" -> "auto") ~
      ("vlsi.inputs.power_spec_type" -> "upf") ~
      ("vlsi.inputs.supplies.power" -> Seq(("name" -> "VDDAIB") ~ ("pins" -> Seq("VDDAIB")))) ~
      ("vlsi.inputs.supplies.ground" -> Seq(("name" -> "VSS") ~ ("pins" -> Seq("VSS"))))

    pretty(render(bumps merge pins merge places merge sdc merge power))
  }

  /** Generates a CSV file that can be imported into a spreadsheet
    * Each cell corresponds to a bump. If the bump has a corresponding core signal,
    * it is printed in the cell as well.
    */
  def toCSV(implicit params: AIB3DParams): String = {
    "Signal <-> Bump\n"+
    // Reverse rows to account for spreadsheet vs. layout
    params.bumpMap.reverse.map{ case r => r.map{ case b =>
      val coreSig = if (b.coreSig.isDefined) b.coreSig.get.fullName + " <-> " else ""
      s"${coreSig}${b.bumpName}"
    }.mkString(", ")}.mkString("\n")
  }

  /** Generates a PNG + PDF file that can be used to visualize the bump map.
    * This uses the scala doodle package and also opens a window for live visualization.
    */
  def toImg(implicit params: AIB3DParams): Unit = {
    require(params.gp.pattern == "square",
      "Only square bump patterns are supported for visualization")
    // Floating point precision
    def roundToNm(x: Double): String = ((x * 1000).round / 1000.0).toString

    // Iterate row-wise (not in reverse order) and column-wise (in reverse order)
    // Scale by factor of 10 for legibility
    val scale = 10.0
    val unitWidth = scale * params.gp.pitchH
    val unitHeight = scale * params.gp.pitchV
    val bumps =  // 2D recursion
      params.bumpMap.foldLeft(Picture.empty)((below, row) =>
        row.reverse.foldLeft(Picture.empty){(right, b) =>
          val bumpText = Picture.text(b.bumpName).scale(scale / 16, scale / 16)
            .above(
              if (b.coreSig.isDefined)
                Picture.text(b.coreSig.get.fullName).scale(scale / 20, scale / 20)
              else Picture.empty
            )
          // An invisible square with a circle inside
          val bumpCircle = Picture.circle(scale * params.gp.pitch / 2)
          val bumpCell = bumpText.on(bumpCircle.fillColor(b match {
            case _: Pwr => Color.red
            case _: Gnd => Color.gray
            case _: TxSig => Color.lightGreen
            case _: RxSig => Color.lightBlue
            case _ => Color.white
          })).on(Picture.rectangle(unitWidth, unitHeight).noFill.noStroke)
          bumpCell.beside(right)
        }.above(below)
      )

    // Overlay a dotted grid for modules
    // Unfortunately, can't get bounding box or size of patch because it's a bug in doodle 0.19.0
    // Fixed for 0.20.0 but that is only available for Scala 3
    // So we have to do it manually - get dimensions of bump map and draw grid
    // (Accounts for default strokeWidth = 1)
    val (bumpsH, bumpsV) = (params.bumpMap(0).length, params.bumpMap.length)
    val bumpsWidth = bumpsH * unitWidth
    val bumpsHeight = bumpsV * unitHeight
    val grid =  // 2D recursion
      (0 until params.modRowsWR).foldLeft(Picture.empty)((below, y) =>
        (0 until params.modColsWR).foldLeft(Picture.empty)((right, x) =>
          Picture.rectangle(bumpsWidth / params.modColsWR - 1,
                            bumpsHeight / params.modRowsWR - 1)
          .strokeColor(Color.gray).strokeDash(Array(scale / 2, scale / 5))
          .beside(right)
        ).above(below)
      )
    // Title
    val titleText = s"""Bump Map: ${bumpsH} x ${bumpsV} bumps at
                        | ${params.gp.pitchH}um x ${params.gp.pitchV}um pitch"""
                        .stripMargin
    val titleBlock = Picture.text(titleText).scale(scale / 5, scale / 5).on(
      Picture.rectangle(bumpsWidth, unitHeight / 2).noFill.noStroke)
    // Rulers
    val leftRulerOffset = if (params.pinSide == "S") params.ip.bumpOffset else 0.0
    val leftRuler =
      (0 until bumpsV).foldLeft(Picture.empty)((below, y) =>
        Picture.text(roundToNm((y + 0.5) * params.gp.pitchV + leftRulerOffset))
        .on(Picture.rectangle(unitWidth, unitHeight).noFill.noStroke)
        .above(below))
      Picture.rectangle(unitWidth / 2, bumpsHeight).noFill.noStroke
    val bottomRulerOffset = if (params.pinSide == "W") params.ip.bumpOffset else 0.0
    val bottomRuler =
      Picture.text("Rulers (um)")
      .on(Picture.rectangle(unitWidth, unitHeight).noFill.noStroke)
      .beside((0 until bumpsH).reverse.foldLeft(Picture.empty)((right, x) =>
        Picture.text(roundToNm((x + 0.5) * params.gp.pitchH + bottomRulerOffset))
        .on(Picture.rectangle(unitWidth, unitHeight).noFill.noStroke)
        .beside(right))
      )
    // Pin placement
    // location is relative to center of bump array
    val pinOffsetX = (params.pinSide match {
      case "W" => -params.ip.bumpOffset * scale
      case "E" => params.ip.bumpOffset * scale
      case _ => 0.0
    }) + unitWidth / 2 - bumpsWidth / 2
    val pinOffsetY = (params.pinSide match {
      case "S" => -params.ip.bumpOffset * scale
      case "N" => params.ip.bumpOffset * scale
      case _ => 0.0
    }) + unitHeight / 2 - bumpsHeight / 2
    val textOffset = scale
    val rotation = params.pinSide match {
      case "N" | "S" => 90.degrees
      case _ => 0.degrees
    }
    val pins = params.flatBumpMap.filter(_.coreSig.isDefined)
      .foldLeft(Picture.empty){ case (on, b) =>
        val coreSig = b.coreSig.get
        val pinSize = params.ip.layerPitch(coreSig.pinLayer.get) / 1000 * scale
        val locX = coreSig.pinLocation.get.x * scale + pinOffsetX
        val locY = coreSig.pinLocation.get.y * scale + pinOffsetY
        val pinText = Picture.text(s"${coreSig.fullName} (${coreSig.pinLayer.get})")
          .scale(scale / 100, scale / 100).rotate(rotation)
        val pinRect = Picture.rectangle(pinSize, pinSize).at(locX, locY)
          .strokeColor(Color.black).strokeWidth(scale / 200)
        (params.pinSide match {
          case "N" => pinText.at(locX, locY + textOffset)
          case "S" => pinText.at(locX, locY - textOffset)
          case "E" => pinText.at(locX + textOffset, locY)
          case "W" => pinText.at(locX - textOffset, locY)
        }).on(pinRect).on(on)
      }

    // Final pic
    val bumpMapPic = titleBlock
                     .above(pins
                       .on(leftRuler
                         .beside(bumps.on(grid))
                       .above(bottomRuler)
                       )
                     )

    // Vector
    bumpMapPic.write[Pdf]("bumpmap.pdf")
    // Scalar
    bumpMapPic.write[Png]("bumpmap.png")
    // Live window (2x2 pixels = 1um^2)
    bumpMapPic.scale(2 / scale, 2 / scale).draw()
  }
}
