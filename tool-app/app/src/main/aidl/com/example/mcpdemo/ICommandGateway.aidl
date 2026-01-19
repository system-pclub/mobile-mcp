package com.example.mcpdemo;

/**
 * Command Gateway interface for MCP.
 */
oneway interface ICommandGateway {
    /**
     * Invokes a command on the application.
     * @param commandJson A json string representing the command.
     */
    void invoke(String commandJson);
}