/**
 *  Public IP Device
 *
 *  Copyright 2021 Dan
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Change History:
 *
 *    Date        Who             What
 *    ----        ---             ----
 *    2021-01-28  Me     It gets a public IP
 * 
 */

metadata {
    definition (name: "Public IP Device", namespace: "djw1191", author: "Dan") {
		capability "Actuator"
		
		command "getPublicIP"
		
		attribute "ipAddress", "string"
    }

    preferences {
		input(name: "enablePoll", type: "bool", title: "Enable public IP polling", defaultValue: true)
		input(name: "enableDebug", type: "bool", title: "Enable debug logging", defaultValue: true)
		input ( name: 'pollInterval', type: 'enum', title: 'Update interval (in minutes)', options: ['1', '5', '10', '15', '30', '60', '180'], required: true, defaultValue: '60' )
    }
}

void installed(){
   log.debug "Installed..."
   initialize()
}

void updated(){
   log.debug "Updated..."
   initialize()
}

void initialize() {
	log.debug "Initializing"
	int disableTime = 1800
   
	unschedule()

	if (enableDebug) {
		log.debug "Debug logging will be automatically disabled in ${disableTime} seconds"
		runIn(disableTime, debugOff)
	}
   
	
	if (enablePoll) {
	
		Integer interval = Integer.parseInt(settings.pollInterval)
		
		switch (interval) {
			case 1: 
				runEvery1Minute(poll)
				break
			case 5:
				runEvery5Minutes(poll)
				break
			case 10:
				runEvery10Minutes(poll)
				break
			case 15:
				runEvery15Minutes(poll)
				break
			case 30:
				runEvery30Minutes(poll)
				break
			case 60:
				runEvery1Hour(poll)
				break
			case 180:
				runEvery3Hours(poll)
				break
			default:
				runIn(interval*60,poll)
				break
		}
		
	    logDebug("Scheduled to run ${interval} minutes") 
		runIn(2, poll) //  Run after updates in addition to the scheduled poll
	}

}

void debugOff() {
   log.warn("Disabling debug logging")
   device.updateSetting("enableDebug", [value:"false", type:"bool"])
}

void logDebug(str) {
   if (settings.enableDebug) log.debug(str)
}

def getPublicIP() {
	poll()
}

def poll() {

	Map params = [
    uri: "http://checkip.amazonaws.com/",
	contentType: "text/plain",
    timeout: 10
    ]
	
	asynchttpGet("httpCallback", params)

}

private void httpCallback(resp, data) {
	logDebug("Parsing get response")
	
	if (resp?.hasError()) {
      log.warn "Error in response. HTTP ${resp.status}."
   }
   
	logDebug("${device.displayName}: httpGetCallback(${groovy.json.JsonOutput.toJson(resp)}, data)")
	logDebug("Recieved data: ${resp.data}")
	logDebug("Recieved status: ${resp.status}")
	logDebug("Recieved headers: ${resp.headers}")
	
	if (resp.status == 200) {
		sendEvent(name: "ipAddress", value: "${resp.data}")
	}

}
