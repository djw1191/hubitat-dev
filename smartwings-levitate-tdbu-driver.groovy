import groovy.transform.Field

/*
 *  SmartWings Levitate Top Down Buttom Up Shade Driver
 *
 *

Changelog:
## [0.1.0] - 2025-01-01 (@djw1191)
  - Initial release

 *  Copyright 2023-2025 
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
*/


@Field static final Map commandClassVersions = [
  0x20: 2, // Basic
  0x22: 1, // Application Status
  0x26: 4, // Switch Multilevel (device supports v4, hub does not)
  0x50: 1, // (Basic) Window Covering
  0x6A: 1, // WindowCovering
  0x55: 1, // Transport Service (device supports v2, hub does not)
  0x59: 1, // Association Grp Info
  0x5A: 1, // Device Reset Locally
  0x5E: 2, // Zwaveplus Info
  0x60: 3, // Multichannel
  0x6C: 1, // Supervision
  0x70: 4, // Configuration
  0x72: 2, // Manufacturer Specific
  0x73: 1, // Powerlevel
  0x7A: 5, // Firmware Update Md
  0x80: 1, // Battery V1
  0x85: 2, // Association
  0x86: 3, // Version
  0x87: 3, // Indicator
  0x8E: 3, // Multi Channel Association
]

metadata {
  definition (name: "SmartWings Day/Night Cellular Shades", namespace: "djw1191", author: "DanW") {
    capability "Battery"
    capability "WindowShade"
    capability "Refresh"
    capability "Configuration"
    capability "Actuator"

	command "partialOpen"

	fingerprint mfr:"045A", prod:"0004", deviceId:"0509", inClusters:"0x00,0x00", controllerType: "ZWV" //SmartWings TDBU Shades
  }

  preferences {
    input "supervisedCmds", "bool",
			title: fmtTitle("Supervised Commands") + "<em> (Experimental)</em>",
			description: fmtDesc("This can increase reliability when the device is paired with security, but may not work correctly on all devices."),
			defaultValue: false
  }
}

void configure() {
	logWarn "configure..."
	setLogLevel(LOG_LEVELS[3], LOG_TIMES[30])   //Force Debug for 30 minutes
	state.deviceSync = true

    if (state.deviceSync || state.resyncAll == null) {
		logWarn "First Configure - syncing settings from device"
		runIn(14, createChildDevices)
	}

	//updateSyncingStatus(8)
	executeProbeCmds()
	runIn(2, executeRefreshCmds)
	runIn(5, executeConfigureCmds)
}

def installed() {
  log.debug "installed()"
  state.deviceSync = true

  checkLogLevel()

}

def updated() {
  log.debug "updated()"
  checkLogLevel()

}

def parse(String description) {
  zwaveParse(description)
}

void executeProbeCmds() {
	logTrace "executeProbeCmds..."

	List<String> cmds = []

	//End Points Check
	if (state.endPoints == null || state.resyncAll) {
		logDebug "Probing for Multiple End Points"
		cmds << secureCmd(zwave.multiChannelV3.multiChannelEndPointGet())
		state.endPoints = 0
	}

	if (cmds) sendCommands(cmds)
}

void executeRefreshCmds() {
	List<String> cmds = []

	if (state.resyncAll || state.deviceSync || !firmwareVersion || !state.deviceModel) {
		cmds << mfgSpecificGetCmd()
		cmds << versionGetCmd()
		state.remove("deviceSync")
	}

	//Refresh Global Stuff
    cmds << batteryGetCmd()

	//Refresh Children
	logTrace "Endpoints: ${endPointList}"
	endPointList.each { endPoint ->
		cmds += getChildRefreshCmds(endPoint)
	}

	if (cmds) sendCommands(cmds,300)
}

void executeConfigureCmds() {
	List<String> cmds = []

	cmds += getConfigureAssocsCmds(true)
	if (cmds) sendCommands(cmds,300)
}

List getChildRefreshCmds(Integer endPoint) {
	List<String> cmds = []
    //Refresh Position
	logTrace "ChildRefresh: ep ${endPoint}"
    	cmds << switchMultilevelGetCmd(endPoint)
	//cmds << windowCoveringGetCmd(endPoint)
	return cmds
}

List getConfigureAssocsCmds(Boolean logging=false) {
	List<String> cmds = []

	if (!state.group1Assoc || state.resyncAll) {
		if (state.group1Assoc == false) {
			if (logging) logDebug "Clearing incorrect lifeline association..."
			cmds << associationRemoveCmd(1,[])
			cmds << secureCmd(zwave.multiChannelAssociationV3.multiChannelAssociationRemove(groupingIdentifier: 1, nodeId:[], multiChannelNodeIds:[]))
		}
		if (logging) logDebug "Setting ${state.endPoints ? 'multi-channel' : 'standard'} lifeline association..."
		if (state.endPoints > 0) {
			cmds << associationRemoveCmd(1,[])
			cmds << secureCmd(zwave.multiChannelAssociationV3.multiChannelAssociationSet(groupingIdentifier: 1, multiChannelNodeIds: [[nodeId: zwaveHubNodeId, bitAddress:0, endPointId: 0]]))
			cmds << mcAssociationGetCmd(1)
		}
		else {
			cmds << associationSetCmd(1, [zwaveHubNodeId])
			cmds << associationGetCmd(1)
		}
	}

	return cmds
}

/*
 *****************************
 * zwaveEvent Event Handlers *
 *****************************
 */

def zwaveEvent(hubitat.zwave.commands.basicv1.BasicReport cmd) {
  logTrace "BasicReport:  ${cmd}"
}

def zwaveEvent(hubitat.zwave.commands.batteryv1.BatteryReport cmd, ep=0) {
	logTrace "${cmd} (ep ${ep})"
    sendBatteryEvents(cmd.batteryLevel)
}

def zwaveEvent(hubitat.zwave.commands.windowcoveringv1.WindowCoveringReport cmd, ep=0) {
    logTrace "WindowCoveringReport:  ${cmd}"
}

def zwaveEvent(hubitat.zwave.commands.switchmultilevelv4.SwitchMultilevelReport cmd, ep=0) {
    logTrace "${cmd} (ep ${ep})"

    sendPositionEvents(cmd.value, ep)

	// Handle reporting of windowShade property
	// Update overall parent value based on real state of shade
	// BU Bar EP = 1, TD Bar EP = 2
	if(cmd.value >= 99) {
		sendWindowShadeEvents("open", ep)
		if(ep == 1) {
			// BU Shade
			// if BU Bar at 99 there is no point checking top position as it must also be at 99
			sendWindowShadeEvents("open", 0)
		} else if(ep == 2) {
			// TD Update
			childDev = getChildByEP(1) //BU Bar Child
			Integer buPos = safeToInt(childDev.currentValue("position"))
			if(buPos == 0) {
				sendWindowShadeEvents("closed", 0)
			}
		}
	} else if (cmd.value == 0) {
		sendWindowShadeEvents("closed", ep)

		if(ep == 2) {
			// TD Update - 0 position assumes open shade
			sendWindowShadeEvents("open", 0)
		} else if(ep == 1) {
			// BU Update
			childDev = getChildByEP(2) //TD Bar Child
			Integer tdPos = safeToInt(childDev.currentValue("position"))

			if(tdPos >=99) { 
				sendWindowShadeEvents("closed", 0)
			}
			else if(tdPos > 0) { 
				sendWindowShadeEvents("partially open", 0)
			}
			else {
				sendWindowShadeEvents("open", 0)
			}
		}
	} else {
		sendWindowShadeEvents("partially open", ep)
		sendWindowShadeEvents("partially open", 0)
	}

}

void zwaveEvent(hubitat.zwave.commands.multichannelv3.MultiChannelCmdEncap cmd) {
	zwaveMultiChannel(cmd)
}
void zwaveEvent(hubitat.zwave.commands.supervisionv1.SupervisionGet cmd, ep=0) {
	zwaveSupervision(cmd,ep)
}

void zwaveEvent(hubitat.zwave.commands.multichannelv3.MultiChannelEndPointReport cmd, ep=0) {
	logTrace "${cmd} (ep ${ep})"

	if (cmd.endPoints > 0) {
		logDebug "Endpoints (${cmd.endPoints}) Detected and Enabled"
		state.endPoints = cmd.endPoints
		createChildDevices()
	}
}

void zwaveEvent(hubitat.zwave.commands.multichannelassociationv3.MultiChannelAssociationReport cmd) {
	logTrace "MultiChannelAssociationReport: ${cmd}"
	//updateSyncingStatus()

	List mcNodes = []
	cmd.multiChannelNodeIds.each {mcNodes += "${it.nodeId}:${it.endPointId}"}

	if (cmd.groupingIdentifier == 1) {
		if (state.endPoints) {
			logDebug "Lifeline Association: ${cmd.nodeId} | MC: ${mcNodes}"
			state.group1Assoc = (mcNodes == ["${zwaveHubNodeId}:0"] ? true : false)
		}
	}
	else {
		logDebug "Unhandled Group: $cmd"
	}
}

void zwaveEvent(hubitat.zwave.commands.associationv2.AssociationReport cmd) {
	logTrace "${cmd}"
	//updateSyncingStatus()

	Integer grp = cmd.groupingIdentifier

	if (grp == 1) {
		if (!state.endPoints) {
			logDebug "Lifeline Association: ${cmd.nodeId}"
			state.group1Assoc = (cmd.nodeId == [zwaveHubNodeId]) ? true : false
		}
	}
	else {
		logDebug "Unhandled Group: $cmd"
	}
}


/*
 ***********************
 * Positioning methods *
 ***********************
 */

def setRailPosition(Integer position, ep=0) {
	if (position == 100) {
		switchMultilevelSetCmd(99, ep)
	} else {
		switchMultilevelSetCmd(position, ep)
	}
    
}

def partialOpen() {

	List<String> cmds = []
	cmds << switchMultilevelSetCmd(60, 2) // Top Rail
	cmds << switchMultilevelSetCmd(0, 1) // Bottom Rail
	sendCommands(cmds, 4000)
	sendWindowShadeEvents("opening", 0)
	sendWindowShadeEvents("opening", 2)
}

/*
 *******************
 * Refresh methods *
 *******************
 */
def refresh() {
    logDebug "Refresh()"

    executeRefreshCmds()
 
}

/*
 ***********************
 * WindowShade methods *
 ***********************
 */


def close(ep=0) {
	if (ep == 0) {
		List<String> cmds = []
		cmds << switchMultilevelSetCmd(99, 2) // Top Rail
		cmds << switchMultilevelSetCmd(0, 1) // Bottom Rail
		sendCommands(cmds, 2000)
  	} else {
		sendCommands(setRailPosition(0, ep))
  }
	sendWindowShadeEvents("closing", ep)
}

def open(ep=0) {
	if (ep == 0) {
		List<String> cmds = []
		cmds << switchMultilevelSetCmd(99, 1) // Bottom Rail
		cmds << switchMultilevelSetCmd(99, 2) // Top Rail
		sendCommands(cmds, 2000)
	} else {
		sendCommands(setRailPosition(99, ep))
  }
	sendWindowShadeEvents("opening", ep)
}

def setPosition(Integer position) {
	setRailPosition(position)
}

def stopPositionChange() {
	logDebug "stopLevelChange()"
	sendCommands(switchMultilevelStopLvChCmd())
}


// direction is "open" or "close"
def startPositionChange(String direction) {
	if(direction == "open") {
    	open()
  	}else if(direction == "close") {
    	close()
  }
}

/*** Child Capabilities ***/
def componentOpen(cd) {
	logDebug "componentOpen from ${cd.displayName} (${cd.deviceNetworkId})"
	ep = getChildEP(cd)
	sendCommands(setRailPosition(99, ep))
	sendWindowShadeEvents("opening", ep)
}

def componentClose(cd) {
	logDebug "componentClose from ${cd.displayName} (${cd.deviceNetworkId})"
	ep = getChildEP(cd)
	sendCommands(setRailPosition(0, ep))
	sendWindowShadeEvents("closing", ep)
}

def componentSetPosition(cd, position) {
	logDebug "componentSetPosition from ${cd.displayName} (${cd.deviceNetworkId}) position: ${position}"
	ep = getChildEP(cd)
	Integer currentPos = safeToInt(cd.currentValue("position"))
	sendCommands(setRailPosition(safeToInt(position), ep))
	if(position < currentPos) {
		sendWindowShadeEvents("closing", ep)
	} else {
		sendWindowShadeEvents("opening", ep)
	}
}

def componentRefresh(cd) {
	logDebug "componentRefresh from ${cd.displayName} (${cd.deviceNetworkId})"
	sendCommands(getChildRefreshCmds(getChildEP(cd)))
}

def componentStopPositionChange(cd) {
	logDebug "componentStopPositionChange from ${cd.displayName} (${cd.deviceNetworkId})"
	sendCommands(switchMultilevelStopLvChCmd(getChildEP(cd)))
}


// direction is "open" or "close"
def componentStartPositionChange(cd, String direction) {
    logDebug "componentStartPositionChange from ${cd.displayName} (${cd.deviceNetworkId})"
	ep = getChildEP(cd)
	Boolean openClose = (direction != "open")
	if(openClose) { sendWindowShadeEvents("closing", ep)}
	else {sendWindowShadeEvents("opening", ep)}
	sendCommands(switchMultilevelStartLvChCmd(openClose, ep))
}


/*******************************************************************
 ***** Child/Other Functions
********************************************************************/
/*** Child Creation Functions ***/
void createChildDevices() {
	logDebug "Checking for child devices (${state.endPoints}) endpoints..."
	endPointList.each { endPoint ->
		if (!getChildByEP(endPoint)) {
			logDebug "Creating new child device for endPoint ${endPoint}, did not find existing"
			addChild(endPoint)
		}
	}
}

void addChild(endPoint, inputType=null) {
	//Driver Settings
	Map deviceType = [namespace:"hubitat", typeName:"Generic Component Window Shade"]
	Map deviceTypeBak = [:]
	Map properties = [name:"${device.name}", isComponent:false, endPoint:"${endPoint}"]

	String dni = getChildDNI(endPoint)
	String epName = "Shade ${endPoint}"
	// properties.type = "R"

	properties.name = "${device.name} - ${epName}"
	logDebug "Creating '${epName}' Child Device"

	def childDev
	try {
		childDev = addChildDevice(deviceType.namespace, deviceType.typeName, dni, properties)
	}
	catch (e) {
		logWarn "The '${deviceType}' driver failed"
		if (deviceTypeBak) {
			logWarn "Defaulting to '${deviceTypeBak}' instead"
			childDev = addChildDevice(deviceTypeBak.namespace, deviceTypeBak.typeName, dni, properties)
		}
	}
}

/*** Child Common Functions ***/
private getChildByEP(endPoint) {
	endPoint = endPoint.toString()
	//Searching using endPoint data value
	def childDev = childDevices?.find { it.getDataValue("endPoint") == endPoint }
	if (childDev) logTrace "Found Child for endPoint ${endPoint} using data.endPoint: ${childDev.displayName} (${childDev.deviceNetworkId})"
	//If not found try deeper search using the child DNIs
	else {
		childDev = childDevices?.find { ch ->
			String ep = null
			List<String> dni = ch.deviceNetworkId.split('-')
			if (dni.size() <= 1) return false
			String dniEp = dni[1]

			//logWarn "getChildByEP dni.size ${dni.size()} -- ${dni}"
			if (dni[2] == "0" || !dni[2])  ep = dniEp  //Default Format DNI-<EP>
			else if (dni[2] == "1")  ep = "${dniEp}S"  //Format DNI-<EP>-1 (Sensor Child)

			//Return true if match found to save child device
			return (ep == endPoint)
		}
		if (childDev) {
			logDebug "Found Child for endPoint ${endPoint} parsing DNI: ${childDev.displayName} (${childDev.deviceNetworkId})"
			//Save the EP on the device so we can find it easily next time
			childDev.updateDataValue("endPoint","$endPoint")
		}
	}
	return childDev
}

private getChildEP(childDev) {
	Integer endPoint = safeToInt(childDev.getDataValue("endPoint")?.replaceAll("[^0-9]+",""))
	if (!endPoint) logWarn "Cannot determine endPoint number for $childDev (defaulting to 0), run Configure to detect existing endPoints"
	return endPoint
}

String getChildDNI(epName) {
	return "${device.deviceId}-${epName}".toUpperCase()
}

List getEndPointList() {
	return (state.endPoints>0 ? 1..(state.endPoints) : [])
}


/*******************************************************************
 ***** Event Senders
********************************************************************/
//evt = [name, value, type, unit, desc, isStateChange]
void sendEventLog(Map evt, ep=0) {
	//Set description if not passed in
	evt.descriptionText = evt.desc ?: "${evt.name} set to ${evt.value} ${evt.unit ?: ''}".trim()

	//Endpoint Events
	if (ep) {
		def childDev = getChildByEP(ep)
	
		if (childDev) {
			if (childDev.currentValue(evt.name).toString() != evt.value.toString() || evt.isStateChange) {
				evt.descriptionText = "${childDev}: ${evt.descriptionText}"
				childDev.parse([evt])
			} else {
				String epName = "Shade ${ep}"
				logDebug "(${epName}) ${evt.descriptionText} [NOT CHANGED]"
				childDev.sendEvent(evt)
			}
		}
		else {
			if (state.deviceSync) { logDebug "No device for endpoint (${ep}) has been created yet..." }
			else { logErr "No device for endpoint (${ep}). Press Save Preferences (or Configure) to create child devices." }
		}
		return
	}
	logTrace "In SendEventLog, after endpoint events have been sent"
	//Main Device Events
	if (device.currentValue(evt.name).toString() != evt.value.toString() || evt.isStateChange) {
		logInfo "${evt.descriptionText}"
	} else {
		logDebug "${evt.descriptionText} [NOT CHANGED]"
	}
	//Always send event to update last activity
	if (evt.name == "position") {
		logTrace "Don't log position to parent, that makes no sense"
	} else {
		logTrace "Sending parent event"
		sendEvent(evt)
	}
	//sendEvent(evt)
}

void sendPositionEvents(rawVal, Integer ep=0) {
	// 99 is the actual zwave max, so if we get that translate to 100
	Integer value = (rawVal == 99 ? 100 : rawVal)
    //Integer value = rawVal
	String desc = "Position set to ${value}" + (type ? " (${type})" : "")
	sendEventLog(name:"position", value:value, unit:"%", desc:desc, ep)
}

void sendWindowShadeEvents(rawVal, Integer ep=0) {
	String value = rawVal
	String desc = "Shade is ${value}" + (type ? " (${type})" : "")
	sendEventLog(name:"windowShade", value:value, desc:desc, ep)
}

void sendBatteryEvents(rawVal) {
	//String value = (rawVal ? "on" : "off")
    Integer value = rawVal
	String desc = "Battery is ${value}" + (type ? " (${type})" : "")
	sendEventLog(name:"battery", value:value, unit:"%", desc:desc, ep)
}

void removeDigital() {
	state.remove("isDigital0")
	endPointList.each { state.remove("isDigital$it" as String) }
}


// Taken from https://github.com/jtp10181/Hubitat/blob/main/Drivers/libraries/zwaveDriverLibrary.groovy

void zwaveParse(String description) {
	hubitat.zwave.Command cmd = zwave.parse(description, commandClassVersions)

	if (cmd) {
		logTrace "parse: ${description} --PARSED-- ${cmd}"
		zwaveEvent(cmd)
	} else {
		logWarn "Unable to parse: ${description}"
	}

	//Update Last Activity
	//updateLastCheckIn()
}

//Decodes Multichannel Encapsulated Commands
void zwaveMultiChannel(hubitat.zwave.commands.multichannelv3.MultiChannelCmdEncap cmd) {
	hubitat.zwave.Command encapsulatedCmd = cmd.encapsulatedCommand(commandClassVersions)
	logTrace "${cmd} --ENCAP-- ${encapsulatedCmd}"

	if (encapsulatedCmd) {
		zwaveEvent(encapsulatedCmd, cmd.sourceEndPoint as Integer)
	} else {
		logWarn "Unable to extract encapsulated cmd from $cmd"
	}
}

//Decodes Supervision Encapsulated Commands (and replies to device)
void zwaveSupervision(hubitat.zwave.commands.supervisionv1.SupervisionGet cmd, ep=0) {
	hubitat.zwave.Command encapsulatedCmd = cmd.encapsulatedCommand(commandClassVersions)
	logTrace "${cmd} --ENCAP-- ${encapsulatedCmd}"

	if (encapsulatedCmd) {
		zwaveEvent(encapsulatedCmd, ep)
	} else {
		logWarn "Unable to extract encapsulated cmd from $cmd"
	}

	sendCommands(secureCmd(zwave.supervisionV1.supervisionReport(sessionID: cmd.sessionID, reserved: 0, moreStatusUpdates: false, status: 0xFF, duration: 0), ep))
}

void zwaveEvent(hubitat.zwave.commands.versionv2.VersionReport cmd) {
	logTrace "${cmd}"

	String fullVersion = String.format("%d.%02d",cmd.firmware0Version,cmd.firmware0SubVersion)
	String zwaveVersion = String.format("%d.%02d",cmd.zWaveProtocolVersion,cmd.zWaveProtocolSubVersion)
	device.updateDataValue("firmwareVersion", fullVersion)
	device.updateDataValue("protocolVersion", zwaveVersion)
	device.updateDataValue("hardwareVersion", "${cmd.hardwareVersion}")

	if (cmd.targetVersions) {
		Map tVersions = [:]
		cmd.targetVersions.each {
			tVersions[it.target] = String.format("%d.%02d",it.version,it.subVersion)
			device.updateDataValue("firmware${it.target}Version", tVersions[it.target])
		}
		logDebug "Received Version Report - Main Firmware: ${fullVersion} | Targets: ${tVersions}"
	}
	else {
		logDebug "Received Version Report - Firmware: ${fullVersion}"
	}
	
	//setDevModel(new BigDecimal(fullVersion))
}

void zwaveEvent(hubitat.zwave.commands.manufacturerspecificv2.ManufacturerSpecificReport cmd) {
	logTrace "${cmd}"

	device.updateDataValue("manufacturer",cmd.manufacturerId.toString())
	device.updateDataValue("deviceType",cmd.productTypeId.toString())
	device.updateDataValue("deviceId",cmd.productId.toString())

	logDebug "fingerprint  mfr:\"${hubitat.helper.HexUtils.integerToHexString(cmd.manufacturerId, 2)}\", "+
		"prod:\"${hubitat.helper.HexUtils.integerToHexString(cmd.productTypeId, 2)}\", "+
		"deviceId:\"${hubitat.helper.HexUtils.integerToHexString(cmd.productId, 2)}\", "+
		"inClusters:\"${device.getDataValue("inClusters")}\""+
		(device.getDataValue("secureInClusters") ? ", secureInClusters:\"${device.getDataValue("secureInClusters")}\"" : "")
}

void zwaveEvent(hubitat.zwave.Command cmd, ep=0) {
	logWarn "Unhandled zwaveEvent: $cmd (ep ${ep}) [${getObjectClassName(cmd)}]"
}


/*******************************************************************
 ***** Z-Wave Command Shortcuts
********************************************************************/
//These send commands to the device either a list or a single command
void sendCommands(List<String> cmds, Long delay=200) {
	sendHubCommand(new hubitat.device.HubMultiAction(delayBetween(cmds, delay), hubitat.device.Protocol.ZWAVE))
}

//Single Command
void sendCommands(String cmd) {
	sendHubCommand(new hubitat.device.HubAction(cmd, hubitat.device.Protocol.ZWAVE))
}

//These Window Covering command classes don't seem to work, 
String windowCoveringGetCmd(Integer ep=0) {
	return secureCmd(zwave.windowCoveringV1.windowCoveringGet(), ep)
}

String windowCoveringStartLvChCmd(Boolean upDown, Integer ep=0) {
	//upDown: false=up, true=down
	return secureCmd(zwave.windowCoveringV1.windowCoveringStartLevelChange(upDown: upDown), ep)
}

//Consolidated zwave command functions so other code is easier to read
String associationSetCmd(Integer group, List<Integer> nodes) {
	return superviseCmd(zwave.associationV2.associationSet(groupingIdentifier: group, nodeId: nodes))
}

String associationRemoveCmd(Integer group, List<Integer> nodes) {
	return superviseCmd(zwave.associationV2.associationRemove(groupingIdentifier: group, nodeId: nodes))
}

String associationGetCmd(Integer group) {
	return secureCmd(zwave.associationV2.associationGet(groupingIdentifier: group))
}

String mcAssociationGetCmd(Integer group) {
	return secureCmd(zwave.multiChannelAssociationV3.multiChannelAssociationGet(groupingIdentifier: group))
}

String versionGetCmd() {
	return secureCmd(zwave.versionV2.versionGet())
}

String mfgSpecificGetCmd() {
	return secureCmd(zwave.manufacturerSpecificV2.manufacturerSpecificGet())
}

String switchBinarySetCmd(Integer value, Integer ep=0) {
	return superviseCmd(zwave.switchBinaryV1.switchBinarySet(switchValue: value), ep)
}

String switchBinaryGetCmd(Integer ep=0) {
	return secureCmd(zwave.switchBinaryV1.switchBinaryGet(), ep)
}

String switchMultilevelSetCmd(Integer value, Integer ep=0) {
	// Removing duration field as its being used for a shade
	logTrace "switchMultilevelSetCmd value ${value} ep ${ep}"
	//return superviseCmd(zwave.switchMultilevelV4.switchMultilevelSet(dimmingDuration: duration, value: value), ep)
	return superviseCmd(zwave.switchMultilevelV4.switchMultilevelSet(value: value), ep)

}

String switchMultilevelGetCmd(Integer ep=0) {
	return secureCmd(zwave.switchMultilevelV4.switchMultilevelGet(), ep)
}

String switchMultilevelStartLvChCmd(Boolean upDown, Integer ep=0) {
	//upDown: false=up, true=down
	return superviseCmd(zwave.switchMultilevelV4.switchMultilevelStartLevelChange(upDown: upDown, ignoreStartLevel:1), ep)
}

String switchMultilevelStopLvChCmd(Integer ep=0) {
	return superviseCmd(zwave.switchMultilevelV4.switchMultilevelStopLevelChange(), ep)
}

String meterGetCmd(meter, Integer ep=0) {
	return secureCmd(zwave.meterV3.meterGet(scale: meter.scale), ep)
}

String meterResetCmd(Integer ep=0) {
	return secureCmd(zwave.meterV3.meterReset(), ep)
}

String wakeUpIntervalGetCmd() {
	return secureCmd(zwave.wakeUpV2.wakeUpIntervalGet())
}

String wakeUpIntervalSetCmd(val) {
	return secureCmd(zwave.wakeUpV2.wakeUpIntervalSet(seconds:val, nodeid:zwaveHubNodeId))
}

String wakeUpNoMoreInfoCmd() {
	return secureCmd(zwave.wakeUpV2.wakeUpNoMoreInformation())
}

String batteryGetCmd() {
	return secureCmd(zwave.batteryV1.batteryGet())
}

String sensorMultilevelGetCmd(sensorType) {
	Integer scale = (temperatureScale == "F" ? 1 : 0)
	return secureCmd(zwave.sensorMultilevelV11.sensorMultilevelGet(scale: scale, sensorType: sensorType))
}

String notificationGetCmd(notificationType, eventType, Integer ep=0) {
	return secureCmd(zwave.notificationV3.notificationGet(notificationType: notificationType, v1AlarmType:0, event: eventType), ep)
}

String configSetCmd(Map param, Integer value) {
	//Convert from unsigned to signed for scaledConfigurationValue
	Long sizeFactor = Math.pow(256,param.size).round()
	if (value >= sizeFactor/2) { value -= sizeFactor }

	return secureCmd(zwave.configurationV1.configurationSet(parameterNumber: param.num, size: param.size, scaledConfigurationValue: value))
}

String configGetCmd(Map param) {
	return secureCmd(zwave.configurationV1.configurationGet(parameterNumber: param.num))
}

List configSetGetCmd(Map param, Integer value) {
	List<String> cmds = []
	cmds << configSetCmd(param, value)
	cmds << configGetCmd(param)
	return cmds
}


/*******************************************************************
 ***** Z-Wave Encapsulation
********************************************************************/
//Secure and MultiChannel Encapsulate
String secureCmd(String cmd) {
	return zwaveSecureEncap(cmd)
}
String secureCmd(hubitat.zwave.Command cmd, ep=0) {
	return zwaveSecureEncap(multiChannelCmd(cmd, ep))
}

//MultiChannel Encapsulate if needed
//This is called from secureCmd or superviseCmd, do not call directly
String multiChannelCmd(hubitat.zwave.Command cmd, ep) {
	//logTrace "multiChannelCmd: ${cmd} (ep ${ep})"
	if (ep > 0) {
		cmd = zwave.multiChannelV3.multiChannelCmdEncap(destinationEndPoint:ep).encapsulate(cmd)
	}
	return cmd.format()
}

//====== Supervision Encapsulate START ======\\
@Field static Map<String, Map<Short, Map>> supervisedPackets = new java.util.concurrent.ConcurrentHashMap()
@Field static Map<String, Short> sessionIDs = new java.util.concurrent.ConcurrentHashMap()
@Field static final Map supervisedStatus = [0x00:"NO SUPPORT", 0x01:"WORKING", 0x02:"FAILED", 0xFF:"SUCCESS"]
@Field static final Integer SUPERVISED_RETRIES = 4
@Field static final Integer SUPERVISED_DELAY_MS = 5000

String superviseCmd(hubitat.zwave.Command cmd, ep=0) {
	//logTrace "superviseCmd: ${cmd} (ep ${ep})"

	if (settings.supervisedCmds) {
		//Encap with SupervisionGet
		Short sID = getSessionId()
		def cmdEncap = zwave.supervisionV1.supervisionGet(sessionID: sID, statusUpdates: false).encapsulate(cmd)

		//Encap with MultiChannel now (if needed) so it is cached that way below
		cmdEncap = multiChannelCmd(cmdEncap, ep)

		logTrace "New Supervised Packet for Session: ${sID}"
		if (supervisedPackets[device.id] == null) { supervisedPackets[device.id] = [:] }
		supervisedPackets[device.id][sID] = [cmd: cmdEncap]

		//Calculate supervisionCheck delay based on how many cached packets
		Integer packetsCount = supervisedPackets[device.id]?.size() ?: 0
		Integer delayTotal = (SUPERVISED_DELAY_MS * packetsCount) + 1000
		runInMillis(delayTotal, supervisionCheck, [data:[sID: sID, num: 1], overwrite:false])

		//Send back secured command
		return secureCmd(cmdEncap)
	}
	else {
		//If supervision disabled just multichannel and secure
		return secureCmd(cmd, ep)
	}
}

Short getSessionId() {
	Short sID = sessionIDs[device.id] ?: (state.supervisionID as Short) ?: 0
	sID = (sID + 1) % 64  // Will always will return between 0-63 (6 bits)
	state.supervisionID = sID
	sessionIDs[device.id] = sID
	return sID
}

//data format: [Short sID, Integer num]
void supervisionCheck(Map data) {
	Short sID = (data.sID as Short)
	Integer num = (data.num as Integer)
	Integer packetsCount = supervisedPackets[device.id]?.size() ?: 0
	logTrace "Supervision Check #${num} Session ${sID}, Packet Count: ${packetsCount}"

	if (supervisedPackets[device.id]?.containsKey(sID)) {
		if (supervisedPackets[device.id][sID].working) {
			logDebug "Supervision Session ${sID} is WORKING status, will not retry"
			supervisedPackets[device.id].remove(sID)
		}
		else {
			List<String> cmds = []
			if (num <= SUPERVISED_RETRIES) { //Keep trying
				logWarn "Re-Sending Supervised Session: ${sID} (Retry #${num})"
				cmds << secureCmd(supervisedPackets[device.id][sID].cmd)
				Integer delayTotal = SUPERVISED_DELAY_MS
				runInMillis(delayTotal, supervisionCheck, [data:[sID: sID, num: num+1], overwrite:false])
			}
			else { //Clear after too many attempts
				logWarn "Supervision MAX RETRIES Reached - device did not respond"
				supervisedPackets[device.id].remove(sID)
			}
			if (cmds) sendCommands(cmds)
		}
	}
	else {
		logTrace "Supervision Session ${sID} has already been cleared or invalid"
	}
}

//Handles reports back from Supervision Encapsulated Commands
void zwaveEvent(hubitat.zwave.commands.supervisionv1.SupervisionReport cmd, ep=0) {
	logTrace "${cmd} (ep ${ep})"
	if (supervisedPackets[device.id] == null) { supervisedPackets[device.id] = [:] }
	Short sID = (cmd.sessionID as Short)
	Integer status = (cmd.status as Integer)

	switch (status) {
		case 0x01: // "Working" - This is as good as success, device got the message
			logDebug "Supervised Command ${supervisedStatus[status]} (sessionID: ${sID})"
			if (supervisedPackets[device.id].containsKey(sID)) {
				supervisedPackets[device.id][sID].working = true
			}
			break
		case 0xFF: // "Success"
			logDebug "Supervised Command ${supervisedStatus[status]} (sessionID: ${sID})"
			supervisedPackets[device.id].remove(sID)
			break
		case 0x00: // "No Support"
		case 0x02: // "Failed"
			logWarn "Supervised Command ${supervisedStatus[status]} (sessionID: ${sID})"
			supervisedPackets[device.id].remove(sID)
			break
	}
}
//====== Supervision Encapsulate END ======\\
/*******************************************************************
 ***** Logging Functions
********************************************************************/
//Logging Level Options
@Field static final Map LOG_LEVELS = [0:"Error", 1:"Warn", 2:"Info", 3:"Debug", 4:"Trace"]
@Field static final Map LOG_TIMES = [0:"Indefinitely", 30:"30 Minutes", 60:"1 Hour", 120:"2 Hours", 180:"3 Hours", 360:"6 Hours", 720:"12 Hours", 1440:"24 Hours"]

/*//Command to set log level, OPTIONAL. Can be copied to driver or uncommented here
command "setLogLevel", [ [name:"Select Level*", description:"Log this type of message and above", type: "ENUM", constraints: LOG_LEVELS],
	[name:"Debug/Trace Time", description:"Timer for Debug/Trace logging", type: "ENUM", constraints: LOG_TIMES] ]
*/

//Additional Preferences
preferences {
	//Logging Options
	input name: "logLevel", type: "enum", title: fmtTitle("Logging Level"),
		description: fmtDesc("Logs selected level and above"), defaultValue: 3, options: LOG_LEVELS
	input name: "logLevelTime", type: "enum", title: fmtTitle("Logging Level Time"),
		description: fmtDesc("Time to enable Debug/Trace logging"),defaultValue: 30, options: LOG_TIMES
}

//Call this function from within updated() and configure() with no parameters: checkLogLevel()
void checkLogLevel(Map levelInfo = [level:null, time:null]) {
	unschedule("logsOff")
	//Set Defaults
	if (settings.logLevel == null) {
		device.updateSetting("logLevel",[value:"3", type:"enum"])
		levelInfo.level = 3
	}
	if (settings.logLevelTime == null) {
		device.updateSetting("logLevelTime",[value:"30", type:"enum"])
		levelInfo.time = 30
	}
	//Schedule turn off and log as needed
	if (levelInfo.level == null) levelInfo = getLogLevelInfo()
	String logMsg = "Logging Level is: ${LOG_LEVELS[levelInfo.level]} (${levelInfo.level})"
	if (levelInfo.level >= 3 && levelInfo.time > 0) {
		logMsg += " for ${LOG_TIMES[levelInfo.time]}"
		runIn(60*levelInfo.time, logsOff)
	}
	logInfo(logMsg)

	//Store last level below Debug
	if (levelInfo.level <= 2) state.lastLogLevel = levelInfo.level
}

//Function for optional command
void setLogLevel(String levelName, String timeName=null) {
	Integer level = LOG_LEVELS.find{ levelName.equalsIgnoreCase(it.value) }.key
	Integer time = LOG_TIMES.find{ timeName.equalsIgnoreCase(it.value) }.key
	device.updateSetting("logLevel",[value:"${level}", type:"enum"])
	checkLogLevel(level: level, time: time)
}

Map getLogLevelInfo() {
	Integer level = settings.logLevel != null ? settings.logLevel as Integer : 1
	Integer time = settings.logLevelTime != null ? settings.logLevelTime as Integer : 30
	return [level: level, time: time]
}

//Legacy Support
void debugLogsOff() {
	device.removeSetting("logEnable")
	device.updateSetting("debugEnable",[value:false, type:"bool"])
}

//Current Support
void logsOff() {
	logWarn "Debug and Trace logging disabled..."
	if (logLevelInfo.level >= 3) {
		Integer lastLvl = state.lastLogLevel != null ? state.lastLogLevel as Integer : 2
		device.updateSetting("logLevel",[value:lastLvl.toString(), type:"enum"])
		logWarn "Logging Level is: ${LOG_LEVELS[lastLvl]} (${lastLvl})"
	}
}

//Logging Functions
void logErr(String msg) {
	log.error "${device.displayName}: ${msg}"
}
void logWarn(String msg) {
	if (logLevelInfo.level>=1) log.warn "${device.displayName}: ${msg}"
}
void logInfo(String msg) {
	if (logLevelInfo.level>=2) log.info "${device.displayName}: ${msg}"
}
void logDebug(String msg) {
	if (logLevelInfo.level>=3) log.debug "${device.displayName}: ${msg}"
}
void logTrace(String msg) {
	if (logLevelInfo.level>=4) log.trace "${device.displayName}: ${msg}"
}

/*** Preference Helpers ***/
String fmtTitle(String str) {
	return "<strong>${str}</strong>"
}
String fmtDesc(String str) {
	return "<div style='font-size: 85%; font-style: italic; padding: 1px 0px 4px 2px;'>${str}</div>"
}

Integer validateRange(val, Integer defaultVal, Integer lowVal, Integer highVal) {
	Integer intVal = safeToInt(val, defaultVal)
	if (intVal > highVal) {
		return highVal
	} else if (intVal < lowVal) {
		return lowVal
	} else {
		return intVal
	}
}

Integer safeToInt(val, defaultVal=0) {
	if ("${val}"?.isInteger())		{ return "${val}".toInteger() }
	else if ("${val}"?.isNumber())	{ return "${val}".toDouble()?.round() }
	else { return defaultVal }
}
