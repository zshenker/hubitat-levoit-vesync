/* 

MIT License

Copyright (c) Niklas Gustafsson

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
*/

// History:
// 
// 2026-01-31: v1.0 Initial support for Levoit LV600S Humidifier

metadata {
    definition(
        name: "Levoit LV600S Humidifier",
        namespace: "zshenker",
        author: "Niklas Gustafsson and elfege (contributor) and Zac Shenker (contributor)",
        description: "Supports controlling the Levoit LV600S (LUH-A603S) humidifier with warm mist support",
        category: "My Apps",
        iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
        iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
        iconX3Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
        documentationLink: "https://github.com/zshenker/hubitat-levoit-vesync")
        {
            capability "Switch"
            capability "Actuator"
            capability "RelativeHumidityMeasurement"
            capability "Refresh"

            attribute "mode", "string"                                 // Humidifier mode (manual/auto/sleep)
            attribute "mistLevel", "number"                            // Mist level (1-9)
            attribute "targetHumidity", "number"                       // Target humidity (30-80%)
            attribute "waterLacks", "string"                           // Water status (ok/low)
            attribute "displayOn", "string"                            // Display status (on/off)
            attribute "autoStop", "string"                             // Automatic stop (on/off)
            attribute "warmMistLevel", "number"                        // Warm mist level (0-3)
            attribute "warmMistEnabled", "string"                      // Warm mist status (on/off)
            attribute "info", "string"                                 // HTML info

            command "setMode", [[name:"Mode*", type: "ENUM", description: "Mode", constraints: ["manual", "auto", "sleep"] ] ]
            command "setMistLevel", [[name:"Level*", type: "NUMBER", description: "Mist Level (1-9)" ] ]
            command "setTargetHumidity", [[name:"Humidity*", type: "NUMBER", description: "Target Humidity (30-80%)" ] ]
            command "setDisplay", [[name:"Display*", type: "ENUM", description: "Display", constraints: ["on", "off"] ] ]
            command "setWarmMistLevel", [[name:"Level*", type: "NUMBER", description: "Warm Mist Level (0-3)" ] ]
            command "toggle"
            command "update"
        }

    preferences {
        input("debugOutput", "bool", title: "Enable debug logging?", defaultValue: false, required: false)
    }
}

def installed() {
    logDebug "Installed with settings: ${settings}"
    updated()
}

def updated() {
    logDebug "Updated with settings: ${settings}"
    state.clear()
    unschedule()
    initialize()

    runIn(3, "update")

    // Turn off debug log in 30 minutes
    if (settings?.debugOutput) runIn(1800, "logDebugOff")
}

def uninstalled() {
    logDebug "Uninstalled app"
}

def initialize() {
    logDebug "initializing"
}

def on() {
    logDebug "on()"
    handlePower(true)
    handleEvent("switch", "on")
    
    // Restore previous settings if available
    if (state.mode != null) {
        runIn(1, "restoreMode")
    }
}

def off() {
    logDebug "off()"
    handlePower(false)
    handleEvent("switch", "off")
}

def toggle() {
    logDebug "toggle()"
    if (device.currentValue("switch") == "on") {
        off()
    } else {
        on()
    }
}

def restoreMode() {
    if (state.mode != null) {
        setMode(state.mode)
    }
}

def setMode(mode) {
    logDebug "setMode(${mode})"
    
    if (!["manual", "auto", "sleep"].contains(mode)) {
        logError "Invalid mode: ${mode}. Must be manual, auto, or sleep"
        return
    }
    
    if (handleMode(mode)) {
        state.mode = mode
        handleEvent("mode", mode)
    }
}

def setMistLevel(level) {
    logDebug "setMistLevel(${level})"
    
    def levelInt = level as Integer
    if (levelInt < 1 || levelInt > 9) {
        logError "Invalid mist level: ${level}. Must be between 1 and 9"
        return
    }
    
    if (handleMistLevel(levelInt)) {
        state.mistLevel = levelInt
        handleEvent("mistLevel", levelInt)
    }
}

def setTargetHumidity(humidity) {
    logDebug "setTargetHumidity(${humidity})"
    
    def humidityInt = humidity as Integer
    if (humidityInt < 30 || humidityInt > 80) {
        logError "Invalid target humidity: ${humidity}. Must be between 30 and 80"
        return
    }
    
    if (handleTargetHumidity(humidityInt)) {
        state.targetHumidity = humidityInt
        handleEvent("targetHumidity", humidityInt)
    }
}

def setDisplay(displayOn) {
    logDebug "setDisplay(${displayOn})"
    
    def enabled = (displayOn == "on")
    if (handleDisplayOn(enabled)) {
        handleEvent("displayOn", displayOn)
    }
}

def setWarmMistLevel(level) {
    logDebug "setWarmMistLevel(${level})"
    
    def levelInt = level as Integer
    if (levelInt < 0 || levelInt > 3) {
        logError "Invalid warm mist level: ${level}. Must be between 0 and 3"
        return
    }
    
    if (handleWarmMistLevel(levelInt)) {
        state.warmMistLevel = levelInt
        handleEvent("warmMistLevel", levelInt)
        handleEvent("warmMistEnabled", levelInt > 0 ? "on" : "off")
    }
}

def update(status, nightLight) {
    logDebug "update(status, nightLight)"

    def result = status?.result
    if (result == null) {
        logError "No status result in update"
        return
    }
    
    updateFromStatus(result)
}

def refresh() {
    logDebug "refresh()"
    parent?.updateDevices()
}

private void updateFromStatus(status) {
    logDebug "updateFromStatus(${status})"
    
    // Basic status - LV600S uses powerSwitch instead of enabled
    def powerOn = (status.powerSwitch == 1)
    handleEvent("switch", powerOn ? "on" : "off")
    
    // Mode - LV600S uses workMode instead of mode
    def mode = mapApiModeToMode(status.workMode)
    if (mode != null) {
        state.mode = mode
        handleEvent("mode", mode)
    }
    
    // Humidity
    if (status.humidity != null) {
        handleEvent("humidity", status.humidity)
    }
    
    // Target humidity
    if (status.targetHumidity != null) {
        state.targetHumidity = status.targetHumidity
        handleEvent("targetHumidity", status.targetHumidity)
    }
    
    // Mist level - LV600S uses mistLevel instead of mist_level
    if (status.mistLevel != null) {
        state.mistLevel = status.mistLevel
        handleEvent("mistLevel", status.mistLevel)
    }
    
    // Water status - LV600S uses waterLacksState instead of water_lacks
    def waterLacks = (status.waterLacksState == 1)
    handleEvent("waterLacks", waterLacks ? "low" : "ok")
    
    // Display - LV600S uses screenSwitch instead of display
    def displayOn = (status.screenSwitch == 1)
    handleEvent("displayOn", displayOn ? "on" : "off")
    
    // Automatic stop - LV600S uses autoStopSwitch
    if (status.autoStopSwitch != null) {
        def autoStopOn = (status.autoStopSwitch == 1)
        handleEvent("autoStop", autoStopOn ? "on" : "off")
    }
    
    // Warm mist - LV600S has warmPower and warmLevel
    if (status.warmLevel != null) {
        state.warmMistLevel = status.warmLevel
        handleEvent("warmMistLevel", status.warmLevel)
        handleEvent("warmMistEnabled", status.warmPower ? "on" : "off")
    }
    
    // Update info display
    updateInfoDisplay(status)
}

private void updateInfoDisplay(status) {
    def html = ""
    
    if (status.humidity != null) {
        html += "Humidity: ${status.humidity}%<br>"
    }
    
    if (status.targetHumidity != null) {
        html += "Target: ${status.targetHumidity}%<br>"
    }
    
    html += "Mist Level: ${state.mistLevel ?: 'N/A'}<br>"
    
    def waterLacks = (status.waterLacksState == 1)
    html += "Water: ${waterLacks ? 'Low' : 'OK'}<br>"
    
    if (status.warmLevel != null && status.warmLevel > 0) {
        html += "Warm Mist: Level ${status.warmLevel}"
    }
    
    handleEvent("info", html)
}

private String mapApiModeToMode(apiMode) {
    switch(apiMode) {
        case "manual":
        case "humidity":
            return "manual"
        case "auto":
            return "auto"
        case "sleep":
            return "sleep"
        default:
            logError "Unknown API mode: ${apiMode}"
            return null
    }
}

private String mapModeToApiMode(mode) {
    switch(mode) {
        case "manual":
            return "manual"
        case "auto":
            return "auto"
        case "sleep":
            return "sleep"
        default:
            return "manual"
    }
}

def handlePower(on) {
    def result = false

    try {
        // LV600S uses powerSwitch and switchIdx instead of enabled and id
        parent.sendBypassRequest(device, [
            data: [powerSwitch: on ? 1 : 0, switchIdx: 0],
            "method": "setSwitch",
            "source": "APP"
        ]) { resp ->
            if (checkHttpResponse("handlePower", resp)) {
                def operation = on ? "ON" : "OFF"
                logDebug "turned ${operation}"
                result = true
            }
        }
    } catch (Exception e) {
        logError "Error in handlePower: ${e.message}"
    }
    
    return result
}

def handleMode(mode) {
    def result = false
    
    def apiMode = mapModeToApiMode(mode)

    // LV600S uses workMode instead of mode
    parent.sendBypassRequest(device, [
        data: ["workMode": apiMode],
        "method": "setHumidityMode",
        "source": "APP"
    ]) { resp ->
        if (checkHttpResponse("handleMode", resp)) {
            logDebug "Set mode to ${mode}"
            result = true
        }
    }
    return result
}

def handleMistLevel(level) {
    def result = false

    // LV600S uses levelIdx, virtualLevel, and levelType
    parent.sendBypassRequest(device, [
        data: [levelIdx: 0, virtualLevel: level, levelType: "mist"],
        "method": "setVirtualLevel",
        "source": "APP"
    ]) { resp ->
        if (checkHttpResponse("handleMistLevel", resp)) {
            logDebug "Set mist level to ${level}"
            result = true
        }
    }
    return result
}

def handleTargetHumidity(humidity) {
    def result = false

    // LV600S uses targetHumidity (camelCase) instead of target_humidity
    parent.sendBypassRequest(device, [
        data: ["targetHumidity": humidity],
        "method": "setTargetHumidity",
        "source": "APP"
    ]) { resp ->
        if (checkHttpResponse("handleTargetHumidity", resp)) {
            logDebug "Set target humidity to ${humidity}"
            result = true
        }
    }
    return result
}

def handleDisplayOn(enabled) {
    def result = false

    // LV600S uses screenSwitch instead of state
    parent.sendBypassRequest(device, [
        data: ["screenSwitch": enabled ? 1 : 0],
        "method": "setDisplay",
        "source": "APP"
    ]) { resp ->
        if (checkHttpResponse("handleDisplayOn", resp)) {
            logDebug "Set display to ${enabled ? 'on' : 'off'}"
            result = true
        }
    }
    return result
}

def handleWarmMistLevel(level) {
    def result = false

    // LV600S uses levelIdx, levelType, mistLevel, and warmLevel for warm mist
    parent.sendBypassRequest(device, [
        data: [
            levelIdx: 0,
            levelType: "warm",
            mistLevel: 0,
            warmLevel: level
        ],
        "method": "setLevel",
        "source": "APP"
    ]) { resp ->
        if (checkHttpResponse("handleWarmMistLevel", resp)) {
            logDebug "Set warm mist level to ${level}"
            result = true
        }
    }
    return result
}

private void handleEvent(name, val) {
    logDebug "handleEvent(${name}, ${val})"
    sendEvent(name: name, value: val)
}

def logDebug(msg) {
    if (settings?.debugOutput) {
        log.debug msg
    }
}

def logError(msg) {
    log.error msg
}

void logDebugOff() {
    //
    // runIn() callback to disable "Debug" logging after 30 minutes
    // Cannot be private
    //
    if (settings?.debugOutput) device.updateSetting("debugOutput", [type: "bool", value: false])
}

def checkHttpResponse(action, resp) {
    if (resp.status == 200 || resp.status == 201 || resp.status == 204) {
        return true
    } else if (resp.status == 400 || resp.status == 401 || resp.status == 404 || resp.status == 409 || resp.status == 500) {
        log.error "${action}: ${resp.status} - ${resp.getData()}"
        return false
    } else {
        log.error "${action}: unexpected HTTP response: ${resp.status}"
        return false
    }
}
