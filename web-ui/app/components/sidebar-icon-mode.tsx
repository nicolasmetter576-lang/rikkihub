import * as React from "react";

import { useSidebar } from "~/components/ui/sidebar";
import { Tooltip, TooltipContent, TooltipTrigger } from "~/components/ui/tooltip";

// 侧边栏收成图标栏（collapsible="icon"）时：带文字的按钮缩成 32px 方块、文字隐藏
export const SIDEBAR_ICON_MODE_BUTTON_CLASS =
  "group-data-[collapsible=icon]:size-8! group-data-[collapsible=icon]:justify-center group-data-[collapsible=icon]:p-0!";
export const SIDEBAR_ICON_MODE_HIDDEN_CLASS = "group-data-[collapsible=icon]:hidden";

interface SidebarIconTooltipProps {
  label: string;
  // 文字本来就看得见的按钮只在收起时提示
  collapsedOnly?: boolean;
  children: React.ReactElement;
}

export function SidebarIconTooltip({
  label,
  collapsedOnly = false,
  children,
}: SidebarIconTooltipProps) {
  const { state, isMobile } = useSidebar();
  const collapsed = state === "collapsed";

  return (
    <Tooltip>
      <TooltipTrigger asChild>{children}</TooltipTrigger>
      <TooltipContent
        side={collapsed ? "right" : "top"}
        hidden={isMobile || (collapsedOnly && !collapsed)}
      >
        {label}
      </TooltipContent>
    </Tooltip>
  );
}
