/**
 * Copyright (C) 2010-2013 Leon Blakey <lord.quackstar at gmail.com>
 *
 * This file is part of PircBotX.
 *
 * PircBotX is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PircBotX is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PircBotX. If not, see <http://www.gnu.org/licenses/>.
 */
package org.pircbotx.exception;

import lombok.Getter;
import org.pircbotx.User;

/**
 * A general exception dealing with Dcc errors
 * @author Leon Blakey <lord.quackstar at gmail.com>
 */
public class DccException extends RuntimeException {
	@Getter
	protected final Reason ourReason;
	@Getter
	protected final User user;

	public DccException(Reason reason, User user, String detail, Throwable cause) {
		super(generateMessage(reason, user, detail), cause);
		this.ourReason = reason;
		this.user = user;
	}

	public DccException(Reason reason, User user, String detail) {
		super(generateMessage(reason, user, detail));
		this.ourReason = reason;
		this.user = user;
	}

	protected static String generateMessage(Reason reason, User user, String detail) {
		return reason + " from user " + user.getNick() + ": " + detail;
	}

	public static enum Reason {
		UnknownFileTransferResume,
		FileTransferCancelled,
		FileTransferTimeout,
		FileTransferResumeTimeout,
		FileTransferResumeCancelled,
		DccPortsInUse
	}
}
