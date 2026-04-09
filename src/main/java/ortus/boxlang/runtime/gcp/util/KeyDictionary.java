/**
 * [BoxLang]
 *
 * Copyright [2023] [Ortus Solutions, Corp]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ortus.boxlang.runtime.gcp.util;

import ortus.boxlang.runtime.scopes.Key;

public class KeyDictionary {

	public static final Key	rawPath						= Key.of( "rawPath" );
	public static final Key	queryStringParameters		= Key.of( "queryStringParameters" );
	public static final Key	requestContext				= Key.of( "requestContext" );
	public static final Key	contentType					= Key.of( "Content-Type" );
	public static final Key	accessControlAllowOrigin	= Key.of( "Access-Control-Allow-Origin" );
	public static final Key	functionName				= Key.of( "functionName" );
	public static final Key	functionVersion				= Key.of( "functionVersion" );
	public static final Key	projectId					= Key.of( "projectId" );
	public static final Key	requestId					= Key.of( "requestId" );

}
