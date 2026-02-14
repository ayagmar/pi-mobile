import readline from "node:readline";

console.error("fake-rpc-process-started");

const stdinReader = readline.createInterface({
  input: process.stdin,
  crlfDelay: Infinity,
});

stdinReader.on("line", (line) => {
  let command;

  try {
    command = JSON.parse(line);
  } catch {
    console.log(
      JSON.stringify({
        type: "response",
        command: "parse",
        success: false,
        error: "invalid json",
      }),
    );
    return;
  }

  console.error(`fake-rpc stderr: ${command.type ?? "unknown"}`);

  console.log(
    JSON.stringify({
      id: command.id,
      type: "response",
      command: command.type ?? "unknown",
      success: true,
      data: {
        echoedType: command.type,
      },
    }),
  );
});

process.on("SIGTERM", () => {
  process.exit(0);
});
