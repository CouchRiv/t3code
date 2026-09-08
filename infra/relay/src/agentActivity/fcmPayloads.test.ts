import { EnvironmentId, ThreadId } from "@t3tools/contracts";
import type { RelayAgentActivityState } from "@t3tools/contracts/relay";
import { describe, expect, it } from "@effect/vitest";

import { makeAggregateState } from "./agentActivityAggregate.ts";
import { androidActivityData, fitFcmData } from "./fcmPayloads.ts";

const state: RelayAgentActivityState = {
  environmentId: EnvironmentId.make("env"),
  threadId: ThreadId.make("thread"),
  projectTitle: "Project",
  threadTitle: "Fix notifications",
  phase: "running",
  headline: "Working",
  modelTitle: "Codex",
  updatedAt: "1970-01-01T00:00:00.000Z",
  deepLink: "/threads/env/thread",
};

function activityData(states: ReadonlyArray<RelayAgentActivityState>) {
  return fitFcmData(
    androidActivityData(
      makeAggregateState({ activeStates: states, terminalState: null, nowMs: 0 }),
    ),
  );
}

describe("Android activity presentation", () => {
  it.each([
    ["waiting_for_approval", "Approval needed"],
    ["waiting_for_input", "Response needed"],
  ] as const)("puts %s before the active count and targets the waiting thread", (phase, title) => {
    const waiting = {
      ...state,
      phase,
      threadId: ThreadId.make("waiting"),
      threadTitle: "Review changes",
      deepLink: "/threads/env/waiting",
    };
    expect(activityData([state, waiting])).toMatchObject({
      activity_title: title,
      activity_subtext: "2 active agents",
      activity_body: expect.stringContaining("Review changes"),
      activity_phase: phase,
      activity_path: waiting.deepLink,
    });
  });

  it("does not imply every waiting agent needs the same kind of response", () => {
    expect(
      activityData([
        { ...state, phase: "waiting_for_input" },
        { ...state, threadId: ThreadId.make("approval"), phase: "waiting_for_approval" },
      ]),
    ).toMatchObject({
      activity_title: "Agents need attention",
      activity_subtext: "2 active agents",
      activity_phase: "waiting_for_input",
      activity_path: state.deepLink,
    });
  });

  it("keeps the total count when more waiting threads exist than display slots", () => {
    const states = Array.from({ length: 6 }, (_, index) => ({
      ...state,
      threadId: ThreadId.make(`waiting-${index}`),
      phase: "waiting_for_approval" as const,
    }));
    expect(activityData(states)).toMatchObject({
      activity_title: "Agents need attention",
      activity_subtext: "6 active agents",
    });
  });

  it.each([
    ["starting", "1 active agent"],
    ["running", "1 active agent"],
    ["stale", "1 active agent"],
    ["completed", "Agent work completed"],
    ["failed", "Agent work failed"],
  ] as const)("preserves the existing %s title without duplicating counts", (phase, title) => {
    expect(activityData([{ ...state, phase }])).toMatchObject({
      activity_title: title,
      activity_subtext: "",
      activity_phase: phase,
      activity_path: state.deepLink,
    });
  });
});
