/*
 * 
 *
 *  ****************  TeslaMate MQTT DRIVER  ****************
 *
 *  Design Usage:
 *  To be used with Teslamate. Hooks into Teslamate provided data over MQTT and stores data in Hubitat
 *
 * Inspiration and helper functions used from MQTT Link Driver (mydevbox, jeubanks, et al) and YAMA
 *  
 *-------------------------------------------------------------------------------------------------------------------
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 * ------------------------------------------------------------------------------------------------------------------------------
 *
 *  Changes:
 *
 *  V0.0.1 - 06/17/21 - Test release 1
 *
 */

import groovy.json.JsonOutput

metadata {
	definition(name: "TeslaMate MQTT DRIVER", namespace: "djw1191", author: "Dan", description: "Teslamate MQTT driver", iconUrl: "", iconX2Url: "", iconX3Url: "") 
	
	{
		capability "Initialize"
		capability "Presence Sensor"

		preferences {
			input(name: "brokerIp", type: "string", title: "MQTT Broker IP Address", description: "example: 192.168.1.111", required: true, displayDuringSetup: true)
			input(name: "brokerPort", type: "string", title: "MQTT Broker Port", description: "example: 1883", required: true, displayDuringSetup: true)
			input(name: "brokerUser", type: "string", title: "MQTT Broker Username", description: "", required: false, displayDuringSetup: true)
			input(name: "brokerPassword", type: "password", title: "MQTT Broker Password", description: "", required: false, displayDuringSetup: true)
			input(name: "periodicConnectionRetry", type: "bool", title: "Periodically attempt re-connection if disconnected", defaultValue: true)
			input(name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false)
			input(name: "enableDesc", type: "bool", title: "Enable descriptionText logging", defaultValue: true)
		}

		command "publish", [[name:"topic*",type:"STRING",description:"Topic"],[name:"mqtt",type:"STRING", description:"Payload"]]
		command "subscribe", [[name:"topic*",type:"STRING", description:"Topic"]]
		command "unsubscribe", [[name:"topic*",type:"STRING", description:"Topic"]]
		command "connect"
		command "disconnect"
		
		attribute "connectionState", "string"
		attribute "charge_port_door_open", "string"
		attribute "frunk_open", "string"
		attribute "trunk_open", "string"
		attribute "geofence", "string"
		attribute "is_climate_on", "string"
		attribute "doors_open", "string"
		attribute "locked", "string"
		attribute "state", "string"
		attribute "outside_temp", "Number"
		attribute "charge_limit_soc", "Number"
		attribute "time_to_full_charge", "Number"
		attribute "update_available", "string"
		attribute "odometer", "Number"
		attribute "usable_battery_level", "Number"
		attribute "inside_temp", "Number"
		attribute "is_user_present", "string"
		attribute "plugged_in", "string"
		attribute "windows_open", "string"
		attribute "shift_state", "string"
		attribute "milesFromHome", "Number"
		attribute "longitude", "Number"
		attribute "latitude", "Number"
		
	}
}

def installed() {
	//log.debug "----- IN INSTALLED -----"
}

def initialize() {
	// log.debug "Initialize called in driver."
	schedule('0 */5 * * * ? *', heartbeat)
	schedule('20 * * ? * *', periodicReconnect)
	//schedule('*/5 * * * * ? *', updateDistance)
	
	int disableTime = 1800
	if (logEnable) {
		log.debug "Debug logging will be automatically disabled in ${disableTime} seconds"
		unschedule(debugOff)
		runIn(disableTime, debugOff)
   }
   
	mqttConnectionAttempt()
	sendEvent(name: "init", value: true, displayed: false)
}

def mqttConnectionAttempt() {
	if (logEnable) log.debug "MQTT Connection Attempt"
 
	if (!interfaces.mqtt.isConnected()) {
		try {   
			interfaces.mqtt.connect("tcp://${settings?.brokerIp}:${settings?.brokerPort}",
							   "hubitat_${getHubId()}", 
							   settings?.brokerUser, 
							   settings?.brokerPassword, 
							   lastWillTopic: "hubitat/${getHubId()}/LWT",
							   lastWillQos: 0, 
							   lastWillMessage: "offline", 
							   lastWillRetain: true)

			// delay for connection
			pauseExecution(1000)

		} catch(Exception e) {
			log.error "In mqttConnectionAttempt: Error initializing."
			if (!interfaces.mqtt.isConnected()) disconnected()
		}
	}

	if (interfaces.mqtt.isConnected()) {
		unschedule(connect)
		connected()
	}
}

def updated() {
	
	int disableTime = 1800
	if (logEnable) {
		log.debug "Debug logging will be automatically disabled in ${disableTime} seconds"
		unschedule(debugOff)
		runIn(disableTime, debugOff)
   }
	disconnect()
	pauseExecution(1000)
	mqttConnectionAttempt()	
	
	schedule('0 */5 * * * ? *', heartbeat)
	//schedule('*/5 * * * * ? *', updateDistance)
	
	unschedule(periodicReconnect)
	schedule('20 * * ? * *', periodicReconnect)
	
}

def debugOff() {
   log.warn("Disabling debug logging")
   device.updateSetting("logEnable", [value:"false", type:"bool"])
}


/////////////////////////////////////////////////////////////////////
// Driver Commands and Functions
/////////////////////////////////////////////////////////////////////

def publish(topic, payload) {
    publishMqtt(topic, payload)
}

def publishMqtt(topic, payload, qos = 0, retained = true) {
    if (!interfaces.mqtt.isConnected()) {
        mqttConnectionAttempt()
    }

    try {
        interfaces.mqtt.publish("hubitat/${getHubId()}/${topic}", payload, qos, retained)
        if (logEnable) log.debug "[publishMqtt] topic: hubitat/${getHubId()}/${topic} payload: ${payload}"
    } catch (Exception e) {
        log.error "In publishMqtt: Unable to publish message."
    }
}

def subscribe(topic) {
    if (!interfaces.mqtt.isConnected()) {
        connect()
    }

    if (logEnable) log.debug "Subscribe to: ${topic}"
    interfaces.mqtt.subscribe("${topic}")
}

def unsubscribe(topic) {
    if (!interfaces.mqtt.isConnected()) {
        connect()
    }
    
    if (logEnable) log.debug "Unsubscribe from: ${topic}"
    interfaces.mqtt.unsubscribe("${topic}")
}

def connect() {
    mqttConnectionAttempt()
}

def connected() {
	log.info "In connected: Connected to broker"
    sendEvent (name: "connectionState", value: "connected")
    publishLwt("online")
	subscribe("teslamate/cars/1/charge_port_door_open")
	subscribe("teslamate/cars/1/frunk_open")
	subscribe("teslamate/cars/1/trunk_open")
	subscribe("teslamate/cars/1/geofence")
	subscribe("teslamate/cars/1/is_climate_on")
	subscribe("teslamate/cars/1/doors_open")
	subscribe("teslamate/cars/1/locked")
	subscribe("teslamate/cars/1/state")
	subscribe("teslamate/cars/1/outside_temp")
	subscribe("teslamate/cars/1/charge_limit_soc")
	subscribe("teslamate/cars/1/time_to_full_charge")
	subscribe("teslamate/cars/1/update_available")
	subscribe("teslamate/cars/1/odometer")
	subscribe("teslamate/cars/1/usable_battery_level")
	subscribe("teslamate/cars/1/inside_temp")
	subscribe("teslamate/cars/1/is_user_present")
	subscribe("teslamate/cars/1/plugged_in")
	subscribe("teslamate/cars/1/windows_open")
	subscribe("teslamate/cars/1/shift_state")
	subscribe("teslamate/cars/1/longitude")
	subscribe("teslamate/cars/1/latitude")
}

def disconnect() {
	unschedule(heartbeat)
	unschedule(updateDistance)

	if (interfaces.mqtt.isConnected()) {
		publishLwt("offline")
		pauseExecution(1000)
		try {
			interfaces.mqtt.disconnect()
			pauseExecution(500)
			disconnected()
		} catch(e) {
			log.warn "Disconnection from broker failed."
			if (interfaces.mqtt.isConnected()) {
				connected()
			}
			else {
				disconnected()
			}
			return;
		}
	} 
	else {
		disconnected()
	}
}

def disconnected() {
	log.info "In disconnected: Disconnected from broker"
	sendEvent (name: "connectionState", value: "disconnected")
}

def publishLwt(String status) {
    publishMqtt("LWT", status)
}

def deviceNotification(message) {
    // This does nothing, but is required for notification capability
}

def updateDistance() {
	
	if ( device.currentValue("state") == "driving") {
		
		def longitude = device.currentValue("longitude")
		def latitude = device.currentValue("latitude")
		
		BigDecimal result = calculateDistance(latitude, longitude)
			
		// Convert from km to miles
		distance = result / 1.609
		distance = distance.setScale(3, BigDecimal.ROUND_HALF_UP)
			
		if (settings.enableDesc == true) log.info("Distance from home is ${distance} miles")
		sendEvent(name: "milesFromHome", value: distance)
		
	}

}
	
/////////////////////////////////////////////////////////////////////
// Parse
/////////////////////////////////////////////////////////////////////

def parse(String event) {
    def message = interfaces.mqtt.parseMessage(event)  
    def (base, cars, carNum, carAttr) = message.topic.tokenize( '/' )
    
    if (logEnable) log.debug "In parse, received message: ${message}"
	//if (logEnable) log.debug "Base: ${base} cars: ${cars} carNum: ${carNum} carAttr: ${carAttr}"
	//if (logEnable) log.debug "carAttr: ${carAttr} ${message.payload}"
	
	switch(carAttr) {
		case "inside_temp":
		
			def f_temp = message.payload.toBigDecimal()
			f_temp = (f_temp * 1.8) + 32
			
			if (logEnable) log.debug "In parse, converting inside_temp to f: ${f_temp}"
			if (settings.enableDesc == true) log.info("${carAttr} is ${f_temp}")
			sendEvent(name: "${carAttr}", value: f_temp)
			break
		case "odometer":
		
			def miles = message.payload.toDouble()
			
			miles = miles / 1.609
			if (settings.enableDesc == true) log.info("${carAttr} is ${miles.round()}")
			sendEvent(name: "${carAttr}", value: miles.round())
			break
		case "outside_temp":
		
			def f_temp = message.payload.toBigDecimal()
			f_temp = (f_temp * 1.8) + 32
			
			if (logEnable) log.debug "In parse, converting outside_temp to f: ${f_temp}"
			if (settings.enableDesc == true) log.info("${carAttr} is ${f_temp}")
			sendEvent(name: "${carAttr}", value: f_temp)
			break
		case "state":
			if (settings.enableDesc == true) log.info("${carAttr} is ${message.payload}")
			sendEvent(name: "${carAttr}", value: message.payload)
			
			if ( message.payload == "driving") {
				scheduleUpdateDistance()
			}
			
			else {
				unscheduleUpdateDistance()
			}
			break
		case "geofence":
			if (logEnable) log.debug "In parse, checking if geofence is empty string"
			if (settings.enableDesc == true) log.info("${carAttr} is ${message.payload}")
			
			if (message.payload?.length() > 0) {
				if (logEnable) log.debug "The length is more than 0!!"
			}
			
			if (message.payload?.isEmpty()) {
				if (logEnable) log.debug "The payload is empty!!!"
			}
			
			if (message.payload?.isEmpty()) {
				runIn(120, setCarAway)
				sendEvent(name: "${carAttr}", value: "N/A")
				//sendEvent(name: "presence", value: "not present")
				if (settings.enableDesc == true) log.info("Delaying car depature by 2 minutes")
			}
			else {
				unschedule(setCarAway)
				sendEvent(name: "${carAttr}", value: message.payload)
				sendEvent(name: "presence", value: "present")
				if (settings.enableDesc == true) log.info("Car has arrived")
			}
			break
		case "latitude":
			if (logEnable) log.debug "In latitude, going to calcuate distance from the house"
			
			def latitude = message.payload.toBigDecimal()
			if (logEnable) log.debug("${carAttr} is ${latitude}")
			sendEvent(name: "${carAttr}", value: latitude)
			
			break
		case "longitude":
			if (logEnable) log.debug "In longitude, going to calcuate distance from the house"
			
			def longitude = message.payload.toBigDecimal()
			sendEvent(name: "${carAttr}", value: longitude)
			if (logEnable) log.debug("${carAttr} is ${longitude}")
			
			break
		default:
			if (settings.enableDesc == true) log.info("${carAttr} is ${message.payload}")
			sendEvent(name: "${carAttr}", value: message.payload)
	}
	
}

BigDecimal calculateDistance(BigDecimal latitudeFrom, BigDecimal longitudeFrom) {
								 
	double EARTH_RADIUS = 6371
	BigDecimal latitudeTo =  39.49968076635477
	BigDecimal longitudeTo = -72.45301854406135
		
        def dLat = Math.toRadians(latitudeFrom - latitudeTo)
        def dLon = Math.toRadians(longitudeFrom - longitudeTo)

        //a = sin²(Δlat/2) + cos(lat1).cos(lat2).sin²(Δlong/2)
        //distance = 2.EARTH_RADIUS.atan2(√a, √(1−a))
        def a = Math.pow(Math.sin(dLat / 2), 2) +
                Math.cos(Math.toRadians(latitudeFrom)) *
                Math.cos(Math.toRadians(latitudeTo)) * Math.pow(Math.sin(dLon / 2), 2)
        return 2 * EARTH_RADIUS * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))

    }


/////////////////////////////////////////////////////////////////////
// Helper Functions
/////////////////////////////////////////////////////////////////////

def setCarAway() {
	sendEvent(name: "presence", value: "not present")
	if (settings.enableDesc == true) log.info("Car has departed")
}


def scheduleUpdateDistance() {

	schedule('*/5 * * * * ? *', updateDistance)

}

def unscheduleUpdateDistance() {
	unschedule(updateDistance)
	
}

def normalize(name) {
    return name.replaceAll("[^a-zA-Z0-9]+","-").toLowerCase()
}

def getHubId() {
    def hub = location.hub
    def hubNameNormalized = normalize(hub.name)
    hubNameNormalized = hubNameNormalized.toLowerCase()
    return hubNameNormalized
}

def heartbeat() {
	if (interfaces.mqtt.isConnected()) {
		publishMqtt("heartbeat", now().toString())
	}				
}

def periodicReconnect() {
	if (settings?.periodicConnectionRetry) {
		if (!interfaces.mqtt.isConnected()) {
			connect()
		}
	}
}

def mqttClientStatus(status) {
	if (logEnable) log.debug "In mqttClientStatus: ${status}"
	if (!interfaces.mqtt.isConnected()) {
		disconnected()
	}
}
