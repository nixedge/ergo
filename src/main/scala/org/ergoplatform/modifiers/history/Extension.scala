package org.ergoplatform.modifiers.history

import com.google.common.primitives.Bytes
import io.circe.syntax._
import io.circe.{Decoder, Encoder, HCursor}
import org.ergoplatform.api.ApiCodecs
import org.ergoplatform.modifiers.BlockSection
import org.ergoplatform.settings.{Algos, Constants}
import scorex.core.ModifierTypeId
import scorex.core.serialization.ScorexSerializer
import scorex.crypto.authds.LeafData
import scorex.crypto.hash.Digest32
import scorex.util.serialization.{Reader, Writer}
import scorex.util.{ModifierId, bytesToId, idToBytes}

/**
  * Extension section of Ergo block. Contains key-value storage
  * represented as Seq[(Array[Byte], Array[Byte])] with mandatory and optional fields.
  *
  * @param headerId - id of corresponding header
  * @param fields   - fields as a sequence of key -> value records. A key is 2-bytes long, value is 64 bytes max.
  */
case class Extension(headerId: ModifierId,
                     override val fields: Seq[(Array[Byte], Array[Byte])],
                     override val sizeOpt: Option[Int] = None)
  extends ExtensionCandidate(fields) with BlockSection {

  override val modifierTypeId: ModifierTypeId = Extension.modifierTypeId

  override def digest: Digest32 = Extension.rootHash(fields)

  override type M = Extension

  override def serializer: ScorexSerializer[Extension] = ExtensionSerializer

  override def toString: String = {
    s"Extension(id: $id, headerId: ${Algos.encode(headerId)}, " +
      s"fields: ${fields.map(kv => s"${Algos.encode(kv._1)} -> ${Algos.encode(kv._2)}")}) "
  }

}

/**
  * Extension block section with not filled header id
  */
class ExtensionCandidate(val fields: Seq[(Array[Byte], Array[Byte])]) {
  def toExtension(headerId: ModifierId): Extension = Extension(headerId, fields)

  def ++(that: ExtensionCandidate): ExtensionCandidate = ExtensionCandidate(fields ++ that.fields)
}

object ExtensionCandidate {
  def apply(fields: Seq[(Array[Byte], Array[Byte])]): ExtensionCandidate = new ExtensionCandidate(fields)
}

object Extension extends ApiCodecs {

  val FieldKeySize: Int = 2
  val FieldValueMaxSize: Int = 64

  //predefined key prefixes
  val SystemParametersPrefix: Byte = 0x00
  val InterlinksVectorPrefix: Byte = 0x01
  val ValidationRulesPrefix: Byte = 0x02

  def rootHash(e: Extension): Digest32 = rootHash(e.fields)

  def rootHash(e: ExtensionCandidate): Digest32 = rootHash(e.fields)

  def rootHash(fields: Seq[(Array[Byte], Array[Byte])]): Digest32 = {
    val elements: Seq[Array[Byte]] = fields.map { f =>
      Bytes.concat(Array(f._1.length.toByte), f._1, f._2)
    }
    Algos.merkleTreeRoot(LeafData @@ elements)
  }

  val modifierTypeId: ModifierTypeId = ModifierTypeId @@ (108: Byte)

  implicit val jsonEncoder: Encoder[Extension] = { e: Extension =>
    Map(
      "headerId" -> Algos.encode(e.headerId).asJson,
      "digest" -> Algos.encode(e.digest).asJson,
      "fields" -> e.fields.map(kv => Algos.encode(kv._1) -> Algos.encode(kv._2).asJson).asJson
    ).asJson
  }

  implicit val jsonDecoder: Decoder[Extension] = { c: HCursor =>
    for {
      headerId <- c.downField("headerId").as[ModifierId]
      fields <- c.downField("fields").as[List[(Array[Byte], Array[Byte])]]
    } yield Extension(headerId, fields)
  }
}

object ExtensionSerializer extends ScorexSerializer[Extension] {

  override def serialize(obj: Extension, w: Writer): Unit = {
    w.putBytes(idToBytes(obj.headerId))
    w.putUShort(obj.fields.size)
    obj.fields.foreach { case (key, value) =>
      w.putBytes(key)
      w.putUByte(value.length)
      w.putBytes(value)
    }
  }

  override def parse(r: Reader): Extension = {
    val startPosition = r.position
    val headerId = bytesToId(r.getBytes(Constants.ModifierIdSize))
    val fieldsSize = r.getUShort()
    val fieldsView = (1 to fieldsSize).toStream.map { _ =>
      val key = r.getBytes(Extension.FieldKeySize)
      val length = r.getUByte()
      val value = r.getBytes(length)
      (key, value)
    }
    val fields = fieldsView.takeWhile(_ => r.position - startPosition < Constants.MaxExtensionSizeMax)
    require(r.position - startPosition < Constants.MaxExtensionSizeMax)
    Extension(headerId, fields, Some(r.position - startPosition))
  }

}