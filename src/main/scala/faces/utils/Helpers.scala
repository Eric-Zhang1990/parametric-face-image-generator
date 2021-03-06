/*
 * Copyright University of Basel, Graphics and Vision Research Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package faces.utils

import java.io.File
import java.net.URI

import breeze.linalg.DenseVector
import faces.settings.FacesSettings
import scalismo.faces.color.RGBA
import scalismo.faces.image.PixelImage
import scalismo.faces.io.{MoMoIO, PixelImageIO, RenderParameterIO}
import scalismo.faces.landmarks.TLMSLandmark2D
import scalismo.faces.momo.MoMo
import scalismo.faces.parameters._
import scalismo.faces.sampling.face.MoMoRenderer
import scalismo.geometry.{Point, Point2D, Vector2D, Vector3D}
import scalismo.utils.Random

import scala.reflect.io.Path

case class Helpers(cfg: FacesSettings)(implicit rnd: Random) {
  import cfg._
  import backgrounds._
  import imageDimensions._
  import morphableModelParameters._
  import outputLocation._

  //****************************************************************************
  // HELPERS
  //****************************************************************************


    // generates outPath if not existing
    if (!Path(outImgPath).exists) {
      Path(outImgPath).createDirectory(failIfExists = false)
    }
    if (!Path(outRpsPath).exists) {
      Path(outRpsPath).createDirectory(failIfExists = false)
    }
    if (!Path(outCSVPath).exists) {
      Path(outCSVPath).createDirectory(failIfExists = false)
    }


  // loading model and choosing neutral part if expressions not used
  val model: MoMo = {
    val momo = MoMoIO.read(new File(modelFn)).get
    if (expressions) momo else momo.neutralModel
  }

  // the renderer for the model
  val renderer: MoMoRenderer = MoMoRenderer(model, RGBA.BlackTransparent).cached(5)

  // generates a random instance of a Morphable Model following Gaussian distributions
  def rndMoMoInstance: MoMoInstance = {
    if (expressions)
      MoMoInstance(IndexedSeq.fill(nShape)(rnd.scalaRandom.nextGaussian()),
        IndexedSeq.fill(nColor)(rnd.scalaRandom.nextGaussian()),
        IndexedSeq.fill(nExpression)(rnd.scalaRandom.nextGaussian()),
        new URI(""))
    else
      MoMoInstance(IndexedSeq.fill(nShape)(rnd.scalaRandom.nextGaussian()),
        IndexedSeq.fill(nColor)(rnd.scalaRandom.nextGaussian()),
        IndexedSeq.fill(nExpression)(0.0),
        new URI(""))
  }


  // adds a random expression to a neutral morphable model instance
  def rndExpressions(id: MoMoInstance): MoMoInstance = {
    id.copy(expression = IndexedSeq.fill(nExpression)(rnd.scalaRandom.nextGaussian()))
  }

  lazy val loadBgs: IndexedSeq[File] = {
    // search all background files
    new File(bgPath).listFiles.filter(_.getName.endsWith(bgType)).toIndexedSeq
  }

  // center the face around a point between the ears and nose (change here if you want to center another point)
  def centerLandmark(rps: RenderParameter): RenderParameter = {
    val lmLeftEar: TLMSLandmark2D = renderer.renderLandmark("left.eye.pupil.center", rps).get
    val lmRightEar: TLMSLandmark2D = renderer.renderLandmark("right.eye.pupil.center", rps).get
    val lmNose: TLMSLandmark2D = renderer.renderLandmark("center.nose.tip", rps).get


    val middleOfFace = (lmLeftEar.point + lmRightEar.point.toVector + lmNose.point.toVector).toVector / 3
    val centerOfImage = Point2D(imageWidth / 2, imageHeight / 2)
    val shift = centerOfImage - middleOfFace

    // shifting by moving the principal point (normalized device coordinates [-1, 1], y axis upwards)
    rps.copy(camera = rps.camera.copy(principalPoint = Point2D(
      rps.camera.principalPoint.x + 2 * shift.x / rps.imageSize.width,
      rps.camera.principalPoint.y - 2 * shift.y / rps.imageSize.height))
    )
  }

  // center the face around chin, eyes, nose and ears and crop a face box (change here if you want to center another point)
  def centerFaceBox(rps: RenderParameter, scaling : Double = 1): RenderParameter = {

    val lmTags = Seq(
      "left.ear.helix.outer",
      "right.ear.helix.outer",
      "center.nose.tip",
      "center.front.trichion",
      "center.chin.tip",
      "left.eyebrow.bend.lower",
      "right.eyebrow.bend.lower"
     )

    val lms = lmTags.map(tag => renderer.renderLandmark(tag, rps).get )


    val xmax = lms.maxBy( _.point.x ).point.x
    val xmin = lms.minBy( _.point.x ).point.x

    val ymax = lms.maxBy( _.point.y ).point.y
    val ymin = lms.minBy( _.point.y ).point.y

    val xcenter = (xmax+xmin)/2.0
    val ycenter = (ymax+ymin)/2.0

    val maxSide = math.max(xmax-xmin, ymax-ymin) * scaling

    val middleOfFace = Point(xcenter, ycenter)

    val centerOfImage = Point2D(imageWidth / 2, imageHeight / 2)

    val lmLeftEar = lms(0)
    val lmRightEar = lms(1)
    val lmNose = lms(2)

    val centerShift= {
      val centerShiftLEar = (lmLeftEar.point- lmNose.point)
      val centerShiftREar = (lmRightEar.point- lmNose.point)

      if (centerShiftLEar.norm > centerShiftREar.norm) {
        centerShiftLEar
      }
      else {
        centerShiftREar
      }

    }
    val shift = middleOfFace - centerOfImage - centerShift*0.2

    val f = maxSide.toDouble/rps.imageSize.width.toDouble
    val g = maxSide.toDouble/rps.imageSize.height.toDouble

    // shifting by moving the principal point (normalized device coordinates [-1, 1], y axis upwards)
    rps.copy(camera = rps.camera.copy(principalPoint = Point2D(
      (rps.camera.principalPoint.x - 2 * shift.x / rps.imageSize.width)/f,
      (rps.camera.principalPoint.y + 2 * shift.y / rps.imageSize.height)/g),
      sensorSize = Vector2D(rps.camera.sensorSize.x * f, rps.camera.sensorSize.y * g))
    )

  }

  // method to write csv file of parameters
  def writeCSV(rps: RenderParameter, file: File): Unit = {

    val vPose: DenseVector[Double] = DenseVector(
      rps.pose.yaw,
      rps.pose.pitch,
      rps.pose.roll,
      rps.pose.translation.x,
      rps.pose.translation.y,
      rps.pose.translation.z
    )
    val vCamera: DenseVector[Double] = DenseVector(
      rps.camera.principalPoint.x,
      rps.camera.principalPoint.y,
      rps.camera.focalLength,
      rps.camera.sensorSize.x,
      rps.camera.sensorSize.y)
    val vMoMo: DenseVector[Double] = DenseVector((rps.momo.shape ++ rps.momo.color ++ rps.momo.expression).toArray)
    val vLight: DenseVector[Double] = rps.environmentMap.toBreezeVector

    val vParam = DenseVector.vertcat(vPose, vCamera, vMoMo, vLight)

    breeze.linalg.csvwrite(file, vParam.toDenseMatrix)
  }

  // method to read csv file and generate a RenderParameter from it to render an image again
  def readCSV(file: File): RenderParameter = {
    val vec = breeze.linalg.csvread(file).toDenseVector
    val init = RenderParameter.default.fitToImageSize(imageWidth, imageHeight)
    val withPose = init.withPose(pose = init.pose.copy(yaw = vec(0), pitch = vec(1), roll = vec(2), translation = Vector3D(vec(3), vec(4), vec(5))))
    val withCam = withPose.withCamera(camera = init.camera.copy(principalPoint = Point2D(vec(6), vec(7)), focalLength = vec(8), sensorSize = Vector2D(vec(9), vec(10))))
    val withMoMo = withCam.withMoMo(momo = MoMoInstance(vec(11 until 11 + nShape).toArray.toIndexedSeq,
      vec(11 + nShape until 11 + nShape + nColor).toArray.toIndexedSeq,
      vec(11 + nShape + nColor until 11 + nShape + nColor + nExpression).toArray.toIndexedSeq,
      new URI("")))
    val withLight = withMoMo.withEnvironmentMap(SphericalHarmonicsLight.fromBreezeVector(vec(11 + nShape + nColor + nExpression until 11 + nShape + nColor + nExpression + 27)))
    withLight
  }

  def write(img: PixelImage[RGBA], rps: RenderParameter, id: Int, n: Int): Unit ={
    if (nSamples > 0){
      PixelImageIO.write(img.map{f=>f.toRGB}, new File(outImgPath + id + "_" + n + ".jpg"))
      RenderParameterIO.write(rps, new File(outRpsPath + id + "_" + n + ".rps"))
      writeCSV(rps, new File(outCSVPath + id + "_" + n + ".csv") )
    }
    else {
      PixelImageIO.write(img.map{f=>f.toRGB}, new File(outImgPath + id + ".jpg"))
      RenderParameterIO.write(rps, new File(outRpsPath + id +  ".rps"))
      writeCSV(rps, new File(outCSVPath + id + ".csv"))
    }

  }
}