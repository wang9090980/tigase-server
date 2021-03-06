/*
 * Tigase Jabber/XMPP Server
 * Copyright (C) 2004-2012 "Artur Hefczyc" <artur.hefczyc@tigase.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 *
 * $Rev: $
 * Last modified by $Author: $
 * $Date: $
 */

/*
 User add script as described in XEP-0133:
 http://xmpp.org/extensions/xep-0133.html#add-user

 AS:Description: Add user
 AS:CommandId: http://jabber.org/protocol/admin#add-user
 AS:Component: sess-man
 */

package tigase.admin

import tigase.server.*
import tigase.util.*
import tigase.xmpp.*
import tigase.db.*
import tigase.xml.*
import tigase.vhosts.*

def JID = "accountjid"
def PASSWORD = "password"
def PASSWORD_VERIFY = "password-verify"
def EMAIL = "email"

def p = (Packet)packet
def auth_repo = (AuthRepository)authRepository
def user_repo = (UserRepository)userRepository
def vhost_man = (VHostManagerIfc)vhostMan
def admins = (Set)adminsSet
def stanzaFromBare = p.getStanzaFrom().getBareJID()
def isServiceAdmin = admins.contains(stanzaFromBare)

def userJid = Command.getFieldValue(packet, JID)
def userPass = Command.getFieldValue(packet, PASSWORD)
def userPassVer = Command.getFieldValue(packet, PASSWORD_VERIFY)
def userEmail = Command.getFieldValue(packet, EMAIL)

if (userJid == null || userPass == null || userPassVer == null || userEmail == null) {
	def result = p.commandResult(Command.DataType.form);

	Command.addTitle(result, "Adding a User")
	Command.addInstructions(result, "Fill out this form to add a user.")

	Command.addFieldValue(result, "FORM_TYPE", "http://jabber.org/protocol/admin",
			"hidden")
	Command.addFieldValue(result, JID, userJid ?: "", "jid-single",
			"The Jabber ID for the account to be added")
	Command.addFieldValue(result, PASSWORD, userPass ?: "", "text-private",
			"The password for this account")
	Command.addFieldValue(result, PASSWORD_VERIFY, userPassVer ?: "", "text-private",
			"Retype password")
	Command.addFieldValue(result, EMAIL, userEmail ?: "", "text-single",
			"Email address")

	return result
}

def result = p.commandResult(Command.DataType.result)
try {
	bareJID = BareJID.bareJIDInstance(userJid)
	VHostItem vhost = vhost_man.getVHostItem(bareJID.getDomain())
	if (isServiceAdmin ||
	(vhost != null && (vhost.isOwner(stanzaFromBare.toString()) || vhost.isAdmin(stanzaFromBare.toString())))) {
		auth_repo.addUser(bareJID, userPass)
		user_repo.setData(bareJID, "email", userEmail);
		Command.addTextField(result, "Note", "Operation successful");
	} else {
		Command.addTextField(result, "Error", "You do not have enough permissions to create account for this domain.");
	}
} catch (UserExistsException ex) {
	Command.addTextField(result, "Note", "User already exists, can't be added.");
} catch (TigaseDBException ex) {
	Command.addTextField(result, "Note", "Problem accessing database, user not added.");
}

return result
