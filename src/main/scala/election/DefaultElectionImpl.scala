/**
 * This file is part of agora-mixnet.
 * Copyright (C) 2015-2016  Agora Voting SL <agora@agoravoting.com>

 * agora-mixnet is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License.

 * agora-mixnet is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.

 * You should have received a copy of the GNU Affero General Public License
 * along with agora-mixnet.  If not, see <http://www.gnu.org/licenses/>.
**/

package election

import shapeless._
import shapeless.syntax.sized._
import shapeless.ops.nat._
import shapeless.ops.nat.LT._
import ch.bfh.unicrypt.math.algebra.multiplicative.classes.GStarModSafePrime
import ch.bfh.unicrypt.crypto.schemes.encryption.classes.ElGamalEncryptionScheme
import ch.bfh.unicrypt.math.algebra.general.classes.Pair
import ch.bfh.unicrypt.crypto.encoder.classes.ZModPrimeToGStarModSafePrime
import scala.concurrent.{ Future, Promise }
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import models._
import app._
import accumulator.ElectionDTOData
import utils.Util

/**
 * The state machine transitions
 *
 * Method signatures allow the compiler to enforce the state machine logic.
 */
trait DefaultElectionImpl extends ElectionTrait
{
  implicit val system = ActorSystem()
  implicit val executor = system.dispatchers.lookup("my-other-dispatcher")
  implicit val materializer = ActorMaterializer()
  // create an election 
  def create[W <: Nat : ToInt](id: String, bits: Int, configOpt : Option[ElectionConfig]) : Future[Election[W, Created]] = {
    val promise = Promise[Election[W, Created]]()
    Future {
      println("Going to start a new Election!")
      val group = GStarModSafePrime.getFirstInstance(bits)
  // import ch.bfh.unicrypt.math.algebra.additive.parameters.ECZModPrimeParameters
  // import ch.bfh.unicrypt.math.algebra.additive.classes.ECZModPrime
  // val group = ECZModPrime.getInstance(ECZModPrimeParameters.SECP521r1)
      val generator = group.getDefaultGenerator()
      val cSettings = CryptoSettings(group, generator)
      val dto = configOpt match {
        case None => 
          val defaultDto = new ElectionDTOData(id.toLong, ToInt[W].apply())
          defaultDto()
        case Some(config) =>
          import java.sql.Timestamp
          val startDate = new Timestamp(2015, 1, 27, 16, 0, 0, 1)
          ElectionDTO(
              id.toLong,
              config,
              ElectionDTOData.REGISTERED,
              startDate,
              startDate,
              None,
              None,
              None,
              false
          )
      }
      val defaultDto = new ElectionDTOData(id.toLong, ToInt[W].apply())
      // the immutable log is the one that fills in the election id, here we just set it to "0" temporarily
      promise.success(new Election[W, Created](Created("0", cSettings, id, dto)))
    } recover { case err =>
      promise.failure(err)
    }
    promise.future
  }

  // now ready to receive shares
  def startShares[W <: Nat : ToInt](in: Election[W, Created]) : Future[Election[W, Shares[_0]]] = {
    Future {
      println("Now waiting for shares")
      new Election[W, Shares[_0]](Shares[_0](List[(String, String)]().sized(0).get, in.state))
    }
  }

  // verify and add a share
  def addShare[W <: Nat : ToInt, T <: Nat : ToInt](in: Election[W, Shares[T]], share: EncryptionKeyShareDTO, proverId: String)(implicit ev: T < W) : Future[Election[W, Shares[Succ[T]]]] = {
    Future {
      println(s"Adding share...")

      val result = Verifier.verifyKeyShare(share, in.state.cSettings, proverId)
      if(result) {
        new Election[W, Shares[Succ[T]]](Shares[Succ[T]](in.state.shares :+ (proverId, share.keyShare), in.state))
      }
      else {
        throw new Exception("Share failed verification")
      }
    }
  }

  // combine the shares into a public key, can only happen if we have all the shares
  def combineShares[W <: Nat : ToInt](in: Election[W, Shares[W]]) : Future[Election[W, Combined]] = {
    Future {
      println("Combining shares..")

      val shares = in.state.shares.map { s =>
        Util.getPublicKeyFromString(s._2, in.state.cSettings.generator)
      }
      val publicKey = shares.reduce( (a,b) => a.apply(b) )

      new Election[W, Combined](Combined(publicKey.convertToString, in.state))
    }
  }

  // start the voting period
  def startVotes[W <: Nat : ToInt](in: Election[W, Combined]) : Future[Election[W, Votes]] = {
    Future {
      println("Now waiting for votes")
      new Election[W, Votes](Votes(List[String](), 0, in.state))
    }
  }

  // votes are cast here
  def addVote[W <: Nat : ToInt](in: Election[W, Votes], vote: String) : Future[Election[W, Votes]] = {
    Future {
      print("+")

      // removed for testing faster
      /*
      val elGamal = ElGamalEncryptionScheme.getInstance(in.state.cSettings.generator)
      // this will throw exception if the vote is invalid
      elGamal.getEncryptionSpace.getElementFromString(vote)
      */

      new Election[W, Votes](Votes(vote :: in.state.votes, in.state.addVoteIndex + 1, in.state))
    }
  }

  // votes are cast here
  def addVotes[W <: Nat : ToInt](in: Election[W, Votes], votes: List[String]) : Future[Election[W, Votes]] = {
    Future {
      print("+")

      // removed for testing faster
      /*
      val elGamal = ElGamalEncryptionScheme.getInstance(in.state.cSettings.generator)
      // this will throw exception if the vote is invalid
      votes.map(elGamal.getEncryptionSpace.getElementFromString(_))
      */

      new Election[W, Votes](Votes(votes ::: in.state.votes, in.state.addVoteIndex + 1, in.state))
    }
  }

  // stop election period
  def stopVotes[W <: Nat : ToInt](in: Election[W, Votes]) : Future[Election[W, VotesStopped]] = {
    Future {
      println("No more votes")
      new Election[W, VotesStopped](VotesStopped(in.state.addVoteIndex , in.state))
    }
  }

  // start mixing
  def startMixing[W <: Nat : ToInt](in: Election[W, VotesStopped]) : Future[Election[W, Mixing[_0]]] = {
    Future {
      println("Now waiting for mixes")
      new Election[W, Mixing[_0]](Mixing[_0](List[ShuffleResultDTO]().sized(0).get, in.state))
    }
  }

  // add a mix by a mixer trustee
  def addMix[W <: Nat : ToInt, T <: Nat : ToInt](in: Election[W, Mixing[T]], mix: ShuffleResultDTO, proverId: String)(implicit ev: T < W) : Future[Election[W, Mixing[Succ[T]]]] = {
    Future {
      println("Adding mix...")
      val elGamal = ElGamalEncryptionScheme.getInstance(in.state.cSettings.generator)
      val keyPairGen = elGamal.getKeyPairGenerator()
      val publicKey = keyPairGen.getPublicKeySpace().getElementFrom(in.state.publicKey)

      println("Convert votes...")

      val now = System.currentTimeMillis

      // will be slightly faster but will not scale over the cluster
      /*
      MPBridge.a()
      val shuffled = mix.votes.par.map( v => elGamal.getEncryptionSpace.getElementFromString(v, true) ).seq
      val votes = in.state match {
        case s: Mixing[_0] => in.state.votes.par.map( v => elGamal.getEncryptionSpace.getElementFromString(v, true) ).seq
        case _ => in.state.mixes.toList.last.votes.par.map( v => elGamal.getEncryptionSpace.getElementFromString(v, true) ).seq
      }
      MPBridge.b()
      */

      val shuffled = mix.votes.par.map( v => Util.getE(elGamal.getEncryptionSpace, v) ).seq
      val votes = in.state match {
        case s: Mixing[_0] => in.state.votes.par.map( v => Util.getE(elGamal.getEncryptionSpace, v) ).seq
        case _ => in.state.mixes.toList.last.votes.par.map( v => Util.getE(elGamal.getEncryptionSpace, v) ).seq
      }
      println(s"vote conversion: [${System.currentTimeMillis - now} ms]")

      /*val (shuffled, votes) = MPBridgeS.ex({
        val shuffled = mix.votes.map( v => elGamal.getEncryptionSpace.getElementFromString(v) ).seq
        val votes = in.state match {
          case s: Mixing[_0] => in.state.votes.map( v => elGamal.getEncryptionSpace.getElementFromString(v) ).seq
          case _ => in.state.mixes.toList.last.votes.map( v => elGamal.getEncryptionSpace.getElementFromString(v) ).seq
        }
        (shuffled, votes)
      }, "1")*/

      println(s"Verifying shuffle..")

      val ok = Verifier.verifyShuffle(Util.tupleFromSeq(votes), Util.tupleFromSeq(shuffled),
        mix.shuffleProof, proverId, publicKey, in.state.cSettings)
      if(!ok) throw new Exception()

      println(s"Verifying shuffle..Ok")

      new Election[W, Mixing[Succ[T]]](Mixing[Succ[T]](in.state.mixes :+ mix, in.state))
    }
  }

  // stop receiving mixes, can only happen if we have all the mixes
  def stopMixing[W <: Nat : ToInt](in: Election[W, Mixing[W]]) : Future[Election[W, Mixed]] = {
    Future {
      println("Mixes done..")
      new Election[W, Mixed](Mixed(in.state))
    }
  }

  // start receiving partial decryptions
  def startDecryptions[W <: Nat : ToInt](in: Election[W, Mixed]) : Future[Election[W, Decryptions[_0]]] = {
    Future {
      println("Now waiting for decryptions")
      new Election[W, Decryptions[_0]](Decryptions[_0](List[PartialDecryptionDTO]().sized(0).get, in.state))
    }
  }

  // verify and add a partial decryption
  def addDecryption[W <: Nat : ToInt, T <: Nat : ToInt](in: Election[W, Decryptions[T]], decryption: PartialDecryptionDTO, proverId: String)(implicit ev: T < W) : Future[Election[W, Decryptions[Succ[T]]]] = {
    Future {
      println("Adding decryption...")

      val elGamal = ElGamalEncryptionScheme.getInstance(in.state.cSettings.generator)
      val votes = in.state.votes.par.map( v => Util.getE(elGamal.getEncryptionSpace, v).asInstanceOf[Pair]).seq

      val sharesMap = in.state.allShares.toMap
      val share = elGamal.getMessageSpace.getElementFrom(sharesMap(proverId))

      val ok = Verifier.verifyPartialDecryption(decryption, votes, in.state.cSettings, proverId, share)
      if(!ok) throw new Exception()

      new Election[W, Decryptions[Succ[T]]](Decryptions[Succ[T]](in.state.decryptions :+ decryption, in.state))
    }
  }

  // combine partial decryptions, can only happen if we have all of them
  def combineDecryptions[W <: Nat : ToInt](in: Election[W, Decryptions[W]]) : Future[Election[W, Decrypted]] = {
    Future {
      println("Combining decryptions...")

      // first convert partial decryptions (a^xi) to elements
      // this yields n lists of decryptions, where n = number of trustees, and there's one decryption per vote
      val decryptionElements = in.state.decryptions.map(
        // FIXME use Util.getE
        // ds => ds.partialDecryptions.par.map(in.state.cSettings.group.getElementFromString(_)).seq
        ds => ds.partialDecryptions.par.map(Util.getE(in.state.cSettings.group, _)).seq
      )
      // combine the list of decryptions:
      // obtain a^-x from individual a^-xi's (example below for n = 2)
      //
      //      === 1 === === 2 ===
      // v1     a^xi      a^xi      = a^x
      // v2     a^xi      a^xi      = a^x
      // v3     a^xi      a^xi      = a^x
      //  .     a^xi      a^xi      = a^x
      //  .
      //
      val combined = decryptionElements.reduce { (a, b) =>
        (a zip b).par.map(c => c._1.apply(c._2)).seq
      }
      println("Combining decryptions...Ok")

      val elGamal = ElGamalEncryptionScheme.getInstance(in.state.cSettings.generator)
      val votes = in.state.votes.par.map( v => Util.getE(elGamal.getEncryptionSpace, v).asInstanceOf[Pair] ).seq
      // a^-x * b = m
      val decrypted = (votes zip combined).par.map(c => c._1.getSecond().apply(c._2)).seq
      val encoder = ZModPrimeToGStarModSafePrime.getInstance(in.state.cSettings.group)

      new Election[W, Decrypted](Decrypted(decrypted.par.map(encoder.decode(_).convertToString).seq, in.state))
    }
  }
}