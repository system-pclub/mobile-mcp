---
title: Mobile MCP Specification
author: Xiheng Li <xhl0724@gmail.com>, Mengting He <mvh6224@psu.edu>, Chengcheng Wan <ccwan@sei.ecnu.edu.cn>, Linhai Song <songlinhai@ict.ac.cn>
type: Standards Track
version: 1.0
created: 2026-02-14
---

## Simple Summary

A standard protocol for Android applications to expose capabilities to on-device AI assistants.


## Abstract

This specification defines the first on-device capability protocol for Android AI assistants. The protocol standardizes how Android applications expose fine-grained tool capabilities to AI agents through the Android Intent mechanism, enabling secure, local, and discoverable capability invocation while preserving operating system security boundaries.


## Motivation

AI assistants increasingly require structured access to mobile application functionality.


## Specification

### 1. Tool Capability Registration
This section defines the registration requirements for a Mobile-MCP compliant Tool service on Android. 

#### 1.1 Service Declaration
A tool MUST expose exactly one Android service component for Mobile-MCP. Furthermore, the service MUST declare the following Intent filter and metadata entries inside the ```<service>``` tag. 

##### 1.1.1 Mobile MCP Intent Filter
``` xml
<intent-filter>
    <action android:name="mobile.mcp.SERVICE" />
</intent-filter>
```

The presence of this Intent filter identifies the service is for Mobile MCP. 

##### 1.1.2 Tool Name Metadata
``` xml
<meta-data android:name="mobile.mcp.tool.name" android:value="XXX" />
```

- Type: string
- Required: YES
- Description: Human-readable tool name

##### 1.1.3 Tool Description Metadata
``` xml
<meta-data  android:name="mobile.mcp.tool.description" android:value="XXX" />
```
 
- Type: string
- Required: YES
- Description: Natural language description of the tool

##### 1.1.4 Capability Descriptor Metadata
``` xml
<meta-data android:name="mobile.mcp.tool.capabilities" android:resource="@xml/mcp_capabilities" />
```

- Type: XML resource reference
- Required: YES
- Description: Points to the capability descriptor file


#### 1.2 Capability Descriptor Resource Format
The capability descriptor MUST be defined as an XML resource file. 

##### 1.2.1 Root element:
``` xml
<mobile-mcp-capabilities version="1.0">
    ...
</mobile-mcp-capabilities>
```
 
The version attribute is required, and it is equal to “1.0” for this specification. 

##### 1.2.2 Capability Definition  
Each capability MUST be declared using the following structure:
``` xml
<capability id="XXX" description="XXX" version="XXX">
    <input>
        <param name="XXX" type="XXX" required="XXX" description="XXX" />
    </input>
    <output>
        <param name="XXX" type="XXX" description="XXX" />
        <param name=”XXX" type="XXX" description="XXX" />
    </output>
</capability>
```

Each capability element MUST include:
- id: unique identifier within the tool
- description: natural language description of the capability
- version: capability version number

The `<input>` element is OPTIONAL. If present, it MAY contain zero or more `<param>` elements. Each `<param>` inside `<input>` MUST define:
- name: parameter name
- type: parameter type
- required: “true” or “false”, representing whether the parameter is mandatory
- description: natural language description of the parameter. 
 
The `<output>` element is OPTIONAL. If present, it MAY contain zero or more `<param>` elements. Each `<param>` inside `<output>` MUST define:
- name: parameter name
- type: parameter type
- description: natural language description of the parameter. 

### 2. Tool Capability Discovery
This section defines how a Mobile-MCP compliant AI assistant/agent discovers registered tools and retrieves their declared capability descriptors. 
 
An Agent MUST discover Mobile-MCP tools using Android’s Intent resolution mechanism. Specifically, it MUST create an Intent with the action string "mobile.mcp.SERVICE", which corresponds to the Intent filter defined in Section 2.1, and then use this Intent to query for Mobile-MCP compliant tools.

``` kotlin
val intent = Intent("mobile.mcp.SERVICE")
val services = packageManager.queryIntentServices(
    intent,
    PackageManager.GET_META_DATA
)
for (service in services) {
   …
}
```

For each resolved service, the Agent MUST retrieve the tool’s human-readable name using the metadata key `mobile.mcp.tool.name`, the natural language description using the metadata key `mobile.mcp.tool.description`, and the resource identifier of the capability descriptor using the metadata key mobile.`mcp.tool.capabilities`, as defined in [Section 1.1](#11-service-declaration).

To obtain the capability descriptor, the Agent MUST first retrieve the target application's ApplicationInfo object and then call PackageManager.getResourcesForApplication(ApplicationInfo) to access the application's resource bundle. The XML descriptor can subsequently be loaded using the Resources.getXml(int) method with the resource identifier obtained from the service metadata. The structure and schema of the capability descriptor XML are defined in [Section 1.2](#12-capability-descriptor-resource-format).
 
The Agent MAY leverage a large language model (LLM) to determine which tool and which specific capability to invoke. The design and operation of such decision-making logic are outside the scope of this specification.

### 3. Tool Capability Invocation
This section defines how an agent MUST invoke a Mobile-MCP capability, how a Mobile-MCP compliant tool MUST handle such invocations, and how an agent MUST interpret the returned results. 
 
#### 3.1 Invocation 
An Agent MUST use a JSON string to specify the capability to be invoked and its corresponding parameters. The JSON payload MUST conform to the following schema.

```json
{
  "mobile-mcp-request": {
    "version": "1.0",
    "request": {
      "id": "UUID",
      "capability": {
        "id": "CAPABILITY_ID",
        "args": {
              "name1": "VALUE1",
              "name2": "VALUE2"
            }
        }
      }
  }
}
```

- request.id: a unique identifier within the agent for this invocation
- capability.id: the identifier of the target capability within the tool
- input: optional; contains zero or more <param> representing the capability inputs
  - name: parameter name, as defined in the tool’s capability descriptor
  - type: parameter type, as defined in the tool’s capability descriptor
  - value: the value for the parameter

An Agent MAY invoke a capability in one of two ways: (1) by establishing a persistent binding to the Tool service and sending messages through the binding, or (2) by sending an explicit Intent to the service. In either case, the Agent MUST use the Tool’s package name and service name to explicitly identify the target service. In addition, a callback mechanism MUST be provided, as required by the Android Intent-based communication model. In our demonstration, we use a PendingIntent as the callback mechanism and implement a class inheriting from BroadcastReceiver to handle the response. These concern transport-level implementation details and are outside the scope of this protocol specification.

#### 3.2 Request Handling
Upon receiving a Mobile-MCP request, the Tool service MUST extract the JSON payload using "mobile-mcp-request" as the Intent extra key. The Tool MUST then validate the version field of the request. The Tool MUST record the request.id and include the same identifier in the corresponding response. Next, the Tool MUST verify that the specified capability.id corresponds to a supported capability. If a matching capability is found, the Tool MUST validate that the input parameters conform to the schema defined in the capability descriptor. If validation succeeds, the Tool MUST invoke the corresponding capability implementation with the provided parameters.
 
The result is encoded in the following schema. 
``` json
{
  "mobile-mcp-response": {
    "version": "1.0",
    "response": {
      "id": "UUID",
      "capability": {
        "id": "CAPABILITY_ID",
        "output": [
            {
              "name": "PARAM_NAME",
              "type": "TYPE",
              "value": "VALUE"
            }
          ]
      },
      "status": "success",
      "message": "Optional error description or success notification."
    }
  }
}
```

- request.id: must be the same as the id of the corresponding request
- capability.id: the identifier of the target capability within the tool
- output: optional; contains zero or more `<param>` representing the capability outputs
  - name: return name, as defined in the tool’s capability descriptor
  - type: return type, as defined in the tool’s capability descriptor
  - value: the value for the return
- status: “success” or “failure”
- message: optional; natural-language description for the failure reason or the successful event.
One possible way to return the result is to create an Intent, attach the JSON response string as an extra, and deliver it by invoking the send(Context, int, Intent) method of the PendingIntent. This implementation detail is not part of the protocol specification. 

#### 3.3 Result Interpretation 
 
The implemented class that extends BroadcastReceiver is responsible for handling the received response. Othe methods are allowed, as this is not part of the protocol specification. 
 
Since an Agent may issue multiple requests to the same Tool, the `request.id` field MUST be used to correlate each response with its corresponding request.