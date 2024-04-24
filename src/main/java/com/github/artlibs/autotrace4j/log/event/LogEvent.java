package com.github.artlibs.autotrace4j.log.event;

import com.github.artlibs.autotrace4j.log.Logger;

/**
 * 功能：日志事件
 *
 * @author suopovate
 * @since 2024/04/27
 * <p>
 * All rights Reserved.
 */
public interface LogEvent {
    Level getLevel();
    String getThreadName();
    Long getEventTime();
    Logger getLogger();
}
