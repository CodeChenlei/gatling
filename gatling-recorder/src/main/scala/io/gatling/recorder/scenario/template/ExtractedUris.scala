/*
 * Copyright 2011-2019 GatlingCorp (https://gatling.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.gatling.recorder.scenario.template

import io.gatling.commons.util.StringHelper._
import io.gatling.http.client.ahc.uri.Uri
import io.gatling.recorder.scenario.{ RequestElement, ScenarioElement }

import com.dongxiguo.fastring.Fastring.Implicits._

private[scenario] case class Value(name: String, value: String)

private[scenario] case class SchemeHost(scheme: String, host: String)

/**
 * Extracts common URIs parts into vals. The algorithm is the following:
 *
 * group by (scheme, authority)
 * inside a group:
 *    if (count > 1) use the longer common root
 *    else use the (scheme, authority)
 * if multiple roots have the same host but different schemes/ports, create a val for the hos
 *
 * @param scenarioElements - contains uris to extracts common parts from
 */
private[scenario] class ExtractedUris(scenarioElements: Seq[ScenarioElement]) {

  private val (values: List[Value], renders: Map[String, Fastring]) = {
    val requestElements = scenarioElements.collect { case elem: RequestElement => elem }

    val allUris =
      (requestElements.map(_.uri) ++ requestElements.flatMap(_.nonEmbeddedResources.map(_.uri)))
        .map(uri => Uri.create(uri)).toList
    val uriGroupedByHost: Map[String, List[Uri]] = allUris.groupBy(uri => uri.getHost)

    val maxNbDigits = uriGroupedByHost.size.toString.length

    var tmpValues: List[Value] = Nil

    val tmpRenders = uriGroupedByHost.zipWithIndex.flatMap {
      case ((_, uris), index) =>

        val valName = "uri" + (index + 1).toString.leftPad(maxNbDigits, "0")

        if (uris.size == 1 || schemesPortAreSame(uris)) {
          val paths = uris.map(uri => uri.getPath)
          val longestCommonPath = longestCommonRoot(paths)

          tmpValues = Value(valName, uris.head.getBaseUrl + longestCommonPath) :: tmpValues
          extractLongestPathUrls(uris, longestCommonPath, valName)

        } else {
          tmpValues = Value(valName, uris.head.getHost) :: tmpValues
          extractCommonHostUrls(uris, valName)
        }
    }

    (tmpValues, tmpRenders)
  }

  private def longestCommonRoot(pathsStrs: List[String]): String = {
    def longestCommonRootRec(sa1: Array[String], sa2: Array[String]): Array[String] = {
      val minLen = math.min(sa1.length, sa2.length)
      var p = 0
      while (p < minLen && sa1(p) == sa2(p)) {
        p += 1
      }

      sa1.slice(0, p)
    }

    val paths = pathsStrs.map(_.split("/"))
    paths.reduce(longestCommonRootRec).toSeq.mkString("/")
  }

  private def extractLongestPathUrls(urls: List[Uri], longestCommonPath: String, valName: String): List[(String, Fastring)] =
    urls.map(url => {
      val restPath = url.getPath.substring(longestCommonPath.length)
      (url.toString, fast"$valName + ${value(s"$restPath${query(url)}")}")
    })

  private def extractCommonHostUrls(uris: List[Uri], valName: String): List[(String, Fastring)] =
    uris.map(uri =>
      (uri.toString, fast""""${uri.getScheme}://${user(uri)}" + $valName + ${value(s"${port(uri)}${uri.getPath}${query(uri)}")}"""))

  private def schemesPortAreSame(uris: Seq[Uri]): Boolean =
    uris.map(uri => uri.getScheme -> uri.getExplicitPort).toSet.size == 1

  private def value(str: String) = fast"${protectWithTripleQuotes(str)}"

  private def query(uri: Uri): Fastring =
    if (uri.getQuery == null) EmptyFastring else fast"?${uri.getQuery}"

  private def user(uri: Uri): Fastring =
    if (uri.getUserInfo == null) EmptyFastring else fast"${uri.getUserInfo}@"

  private def port(uri: Uri): Fastring =
    if (uri.getPort != -1 && uri.getPort != uri.getSchemeDefaultPort) fast":${uri.getPort}" else EmptyFastring

  def vals: List[Value] = values

  def renderUri(uri: String): Fastring =
    if (renders.contains(uri)) renders(uri) else fast"$uri"
}
