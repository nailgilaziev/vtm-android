/*
 * Copyright 2013 Hannes Janetzek
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.oscim.utils.overpass;

import static org.oscim.core.osm.TagGroup.EMPTY_TAG_GROUP;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import org.oscim.core.osm.Bound;
import org.oscim.core.osm.OSMData;
import org.oscim.core.osm.OSMElement;
import org.oscim.core.osm.OSMMember;
import org.oscim.core.osm.OSMNode;
import org.oscim.core.osm.OSMRelation;
import org.oscim.core.osm.OSMWay;
import org.oscim.core.osm.TagGroup;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

public class OverpassAPIReader  {
	private static final String OVERPASS_API = "http://city.informatik.uni-bremen.de/oapi/interpreter";
	private static final int RESPONSECODE_OK = 200;

	/**
	 * The timeout we use for the HttpURLConnection.
	 */
	private static final int TIMEOUT = 15000;

	/**
	 * The base url of the server. Defaults to.
	 * "http://www.openstreetmap.org/api/0.5".
	 */
	private final String myBaseUrl = OVERPASS_API;

	/**
	 * The http connection used to retrieve data.
	 */
	private HttpURLConnection myActiveConnection;

	/**
	 * The stream providing response data.
	 */
	private InputStream responseStream;

	private final String query;

	/**
	 * Creates a new instance with the specified geographical coordinates.
	 *
	 * @param left
	 *            The longitude marking the left edge of the bounding box.
	 * @param right
	 *            The longitude marking the right edge of the bounding box.
	 * @param top
	 *            The latitude marking the top edge of the bounding box.
	 * @param bottom
	 *            The latitude marking the bottom edge of the bounding box.
	 * @param baseUrl
	 *            (optional) The base url of the server (eg.
	 *            http://www.openstreetmap.org/api/0.5).
	 */
	public OverpassAPIReader(final double left, final double right,
			final double top, final double bottom, final String baseUrl,
			final String query) {

		String bbox = "(" + Math.min(top, bottom) + "," + Math.min(left, right)
				+ "," + Math.max(top, bottom) + "," + Math.max(left, right)
				+ ")";

		this.query = query.replaceAll("\\{\\{bbox\\}\\}", bbox);

	}

	/**
	 * Open a connection to the given url and return a reader on the input
	 * stream from that connection.
	 *
	 * @param pUrlStr
	 *            The exact url to connect to.
	 * @return An reader reading the input stream (servers answer) or
	 *         <code>null</code>.
	 * @throws IOException
	 *             on io-errors
	 */
	private InputStream getInputStream(final String pUrlStr) throws IOException {
		URL url;
		int responseCode;
		String encoding;

		url = new URL(pUrlStr);
		myActiveConnection = (HttpURLConnection) url.openConnection();

		myActiveConnection.setRequestProperty("Accept-Encoding",
				"gzip, deflate");

		responseCode = myActiveConnection.getResponseCode();

		if (responseCode != RESPONSECODE_OK) {
			String message;
			String apiErrorMessage;

			apiErrorMessage = myActiveConnection.getHeaderField("Error");

			if (apiErrorMessage != null) {
				message = "Received API HTTP response code " + responseCode
						+ " with message \"" + apiErrorMessage
						+ "\" for URL \"" + pUrlStr + "\".";
			} else {
				message = "Received API HTTP response code " + responseCode
						+ " for URL \"" + pUrlStr + "\".";
			}

			throw new IOException(message);
		}

		myActiveConnection.setConnectTimeout(TIMEOUT);

		encoding = myActiveConnection.getContentEncoding();

		responseStream = myActiveConnection.getInputStream();
		if (encoding != null && encoding.equalsIgnoreCase("gzip")) {
			responseStream = new GZIPInputStream(responseStream);
		} else if (encoding != null && encoding.equalsIgnoreCase("deflate")) {
			responseStream = new InflaterInputStream(responseStream,
					new Inflater(true));
		}

		return responseStream;
	}

	class TmpRelation {
		Long id;
		String type;
		String role;
	}

	private final List<Bound> bounds = new ArrayList<Bound>();
	private Map<Long, OSMNode> nodesById = new HashMap<Long, OSMNode>();
	private Map<Long, OSMWay> waysById = new HashMap<Long, OSMWay>();
	private Map<Long, OSMRelation> relationsById = new HashMap<Long, OSMRelation>();
	private Map<OSMRelation, List<TmpRelation>> relationMembersForRelation =
			new HashMap<OSMRelation, List<TmpRelation>>();

	private final Collection<OSMNode> ownNodes = new ArrayList<OSMNode>(10000);
	private final Collection<OSMWay> ownWays = new ArrayList<OSMWay>(1000);
	private final Collection<OSMRelation> ownRelations = new ArrayList<OSMRelation>(
			100);

	public void parse(InputStream in) throws IOException {
		JsonFactory jsonFactory = new JsonFactory();
		try {
			JsonParser jp = jsonFactory.createJsonParser(in);

			JsonToken t;
			while ((t = jp.nextToken()) != null) {
				if (t == JsonToken.START_OBJECT) {
					jp.nextToken();

					String name = jp.getCurrentName();
					jp.nextToken();

					if ("type".equals(name)) {
						String type = jp.getText();

						if ("node".equals(type))
							parseNode(jp);

						else if ("way".equals(type))
							parseWay(jp);

						else if ("relation".equals(type))
							parseRelation(jp);
					}
				}
			}
		} catch (JsonParseException e) {
			e.printStackTrace();
		}
	}

	private void parseNode(JsonParser jp) throws JsonParseException,
			IOException {

		long id = 0;
		double lat = 0, lon = 0;
		TagGroup tags = EMPTY_TAG_GROUP;

		while (jp.nextToken() != JsonToken.END_OBJECT) {

			String name = jp.getCurrentName();
			jp.nextToken();

			if ("id".equals(name))
				id = jp.getLongValue();

			else if ("lat".equals(name))
				lat = jp.getDoubleValue();

			else if ("lon".equals(name))
				lon = jp.getDoubleValue();

			else if ("tags".equals(name))
				tags = parseTags(jp);

		}

		// log("node: "+id + " "+ lat + " " + lon);
		OSMNode node = new OSMNode(lat, lon, tags, id);
		ownNodes.add(node);
		nodesById.put(Long.valueOf(id), node);
	}

	private void parseWay(JsonParser jp) throws JsonParseException, IOException {

		long id = 0;
		TagGroup tags = EMPTY_TAG_GROUP;
		ArrayList<OSMNode> wayNodes = new ArrayList<OSMNode>();

		while (jp.nextToken() != JsonToken.END_OBJECT) {

			String name = jp.getCurrentName();
			jp.nextToken();

			if ("id".equals(name))
				id = jp.getLongValue();

			else if ("nodes".equals(name)) {
				while (jp.nextToken() != JsonToken.END_ARRAY) {
					Long nodeId = Long.valueOf(jp.getLongValue());

					OSMNode node = nodesById.get(nodeId);
					if (node != null)
						// log("missing node " + nodeId);
						// else
						wayNodes.add(node);
				}
			} else if ("tags".equals(name))
				tags = parseTags(jp);
		}

		// log("way: "+ id + " " + wayNodes.size());
		OSMWay way = new OSMWay(tags, id, wayNodes);
		ownWays.add(way);
		waysById.put(Long.valueOf(id), way);
	}

	private void parseRelation(JsonParser jp) throws JsonParseException,
			IOException {

		long id = 0;
		TagGroup tags = EMPTY_TAG_GROUP;
		ArrayList<TmpRelation> members = new ArrayList<TmpRelation>();

		while (jp.nextToken() != JsonToken.END_OBJECT) {

			String name = jp.getCurrentName();
			jp.nextToken();

			if ("id".equals(name))
				id = jp.getLongValue();

			else if ("members".equals(name)) {
				while (jp.nextToken() != JsonToken.END_ARRAY) {
					TmpRelation member = new TmpRelation();

					while (jp.nextToken() != JsonToken.END_OBJECT) {
						name = jp.getCurrentName();
						jp.nextToken();

						if ("type".equals(name))
							member.type = jp.getText();

						else if ("ref".equals(name))
							member.id = Long.valueOf(jp.getLongValue());

						else if ("role".equals(name))
							member.role = jp.getText();
					}
					members.add(member);
				}
			} else if ("tags".equals(name))
				tags = parseTags(jp);
		}

		OSMRelation relation = new OSMRelation(tags, id, members.size());
		ownRelations.add(relation);
		relationsById.put(Long.valueOf(id), relation);
		relationMembersForRelation.put(relation, members);
	}

	private static TagGroup parseTags(JsonParser jp) throws JsonParseException,
			IOException {

		Map<String, String> tagMap = null;

		while (jp.nextToken() != JsonToken.END_OBJECT) {
			String key = jp.getCurrentName();
			jp.nextToken();
			String val = jp.getText();
			if (tagMap == null)
				tagMap = new HashMap<String, String>(10);

			tagMap.put(key, val);

		}
		if (tagMap == null)
			return EMPTY_TAG_GROUP;

		return new TagGroup(tagMap);
	}

	private static void log(String msg) {
		System.out.println(msg);
	}


	public OSMData getData() {

		String encoded;
		try {
			encoded = URLEncoder.encode(this.query, "utf-8");
		} catch (UnsupportedEncodingException e1) {
			e1.printStackTrace();
			return null;
		}
		System.out.println(myBaseUrl + "?data=" + encoded);

		InputStream inputStream = null;

		try {
			inputStream = getInputStream(myBaseUrl + "?data=[out:json];" + encoded);

			parse(inputStream);
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if (inputStream != null)
				try {
					inputStream.close();
				} catch (IOException e) {
					//...
				}
			inputStream = null;
		}

		for (Entry<OSMRelation, List<TmpRelation>> entry : relationMembersForRelation
				.entrySet()) {

			OSMRelation relation = entry.getKey();

			for (TmpRelation member : entry.getValue()) {

				OSMElement memberObject = null;

				if ("node".equals(member)) {
					memberObject = nodesById.get(member.id);
				} else if ("way".equals(member)) {
					memberObject = waysById.get(member.id);
				} else if ("relation".equals(member)) {
					memberObject = relationsById.get(member.id);
				} else {
					// log("missing relation " + member.id);
					continue;
				}

				if (memberObject != null) {
					OSMMember ownMember = new OSMMember(member.role,
							memberObject);

					relation.relationMembers.add(ownMember);
				}
			}
		}
		log("nodes: " + ownNodes.size() + " ways: " + ownWays.size()
				+ " relations: " + ownRelations.size());

		// give up references to original collections
		nodesById = null;
		waysById = null;
		relationsById = null;
		relationMembersForRelation = null;

		return new OSMData(bounds, ownNodes, ownWays, ownRelations);
	}
}
