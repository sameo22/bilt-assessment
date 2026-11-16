const toneClasses = {
  success: "border-emerald-200 bg-emerald-50 text-emerald-800",
  neutral: "border-slate-200 bg-slate-50 text-slate-700",
  warning: "border-amber-200 bg-amber-50 text-amber-800",
};

const numberFormatter = new Intl.NumberFormat("en-US");

function formatPoints(points) {
  return `${numberFormatter.format(points)} ${points === 1 ? "point" : "points"}`;
}

// Title, description and tone for each outcome from the RewardsEngine.
// Only AWARDED gets to say points were credited.
function statusFor(result, member) {
  switch (result.outcome) {
    case "AWARDED":
      return {
        title: `${formatPoints(result.pointsAwarded)} credited`,
        description: "Your rent payment was processed successfully.",
        tone: "success",
      };
    case "DUPLICATE":
      return {
        title: "Duplicate event skipped",
        description:
          "We already received this payment, so no additional points were credited.",
        tone: "neutral",
      };
    case "CAPPED":
      return {
        title: "Monthly cap reached",
        description: `You've earned the maximum of ${formatPoints(member.monthlyCap)} this month, so this payment didn't earn additional points.`,
        tone: "warning",
      };
    default:
      // Don't say "credited" for something we don't recognize.
      return {
        title: "Payment received",
        description: "We couldn't confirm the points for this payment yet.",
        tone: "neutral",
      };
  }
}

export function buildViewModel(result, member) {
  const progressPercent = Math.min(
    100,
    (member.pointsThisMonth / member.monthlyCap) * 100,
  );

  return { ...statusFor(result, member), progressPercent };
}

export function renderDashboard(result, member) {
  const view = buildViewModel(result, member);

  document.querySelector("[data-status]").className =
    `rounded-2xl border p-5 ${toneClasses[view.tone]}`;
  document.querySelector("[data-status-title]").textContent = view.title;
  document.querySelector("[data-status-description]").textContent =
    view.description;
  document.querySelector("[data-points]").textContent = numberFormatter.format(
    member.pointsThisMonth,
  );
  document.querySelector("[data-streak]").textContent =
    `${member.streakMonths} month streak`;
  document.querySelector("[data-progress]").style.width =
    `${view.progressPercent}%`;
  document.querySelector("[data-progress-label]").textContent =
    `${Math.round(view.progressPercent)}% of monthly cap`;
}
