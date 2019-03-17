package com.lightning.walletapp.test

import java.nio.ByteOrder

import com.lightning.walletapp.ln._
import com.lightning.walletapp.ln.wire.Hop
import fr.acinq.bitcoin.{Btc, Crypto, MilliBtc, MilliSatoshi, Protocol, Satoshi}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import scodec.bits.ByteVector

/**
  * Created by anton on 13.07.17.
  */
class PaymentRequestSpec {

  def allTests = {
    import com.lightning.walletapp.ln.PaymentRequest._

    val priv = PrivateKey(ByteVector.fromValidHex("e126f68f7eafcc8b74f54d269fe206be715000f94dac067d1c04a8ca3b2db734"), compressed = true)
    val pub = priv.publicKey
    val nodeId = pub
    assert(nodeId == PublicKey(ByteVector.fromValidHex("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")))

    {
      println("check minimal unit is used")
      assert('p' == Amount.unit(MilliSatoshi(1)))
      assert('p' == Amount.unit(MilliSatoshi(99)))
      assert('n' == Amount.unit(MilliSatoshi(100)))
      assert('p' == Amount.unit(MilliSatoshi(101)))
      assert('n' == Amount.unit(Satoshi(1)))
      assert('u' == Amount.unit(Satoshi(100)))
      assert('n' == Amount.unit(Satoshi(101)))
      assert('u' == Amount.unit(Satoshi(1155400)))
      assert('m' == Amount.unit(MilliBtc(1)))
      assert('m' == Amount.unit(MilliBtc(10)))
      assert('m' == Amount.unit(Btc(1)))
    }

    {
      println("check that we can still decode non-minimal amount encoding")
      assert(Some(MilliSatoshi(100000000)) == Amount.decode("1000u"))
      assert(Some(MilliSatoshi(100000000)) == Amount.decode("1000000n"))
      assert(Some(MilliSatoshi(100000000)) == Amount.decode("1000000000p"))
    }

//    {
//      println("Pay 1 BTC without multiplier")
//      val ref = "lnbc11pdk67ujpp5gdvqef4hmd3g2djev34fl3uhz0rc6v403v8gzpwf2c6mzncmz0qsdqsf4ujqarfwqsxymmccqp29v9ej57ruyrf87twdat34z9wuug8gk0ukftu6kr3cpvvzw4xu6736922360favck2caryehmvz7d73z4dpgyr4vsmcvsna0y3qvr67qqr7tdjc"
//      val pr = PaymentRequest.read(ref)
//      assert(pr.amount == Some(MilliSatoshi(1 * 100000000000L)))
//    }

    {
      println("Please make a donation of any amount using payment_hash 0001020304050607080900010203040506070809000102030405060708090102 to me @03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")
      val ref = "lnbc1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq8rkx3yf5tcsyz3d73gafnh3cax9rn449d9p5uxz9ezhhypd0elx87sjle52x86fux2ypatgddc6k63n7erqz25le42c4u4ecky03ylcqca784w"
      val pr = PaymentRequest.read(ref)
      assert(pr.prefix == "lnbc")
      assert(pr.amount.isEmpty)
      assert(pr.paymentHash == ByteVector.fromValidHex("0001020304050607080900010203040506070809000102030405060708090102"))
      assert(pr.timestamp == 1496314658L)
      assert(pr.nodeId == PublicKey(ByteVector.fromValidHex("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")))
      assert(pr.tags.size == 2)
      assert(PaymentRequest.write(pr.sign(priv)) == ref)
    }

    {
      println("Please send $3 for a cup of coffee to the same peer, within 1 minute")
      val ref = "lnbc2500u1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpuaztrnwngzn3kdzw5hydlzf03qdgm2hdq27cqv3agm2awhz5se903vruatfhq77w3ls4evs3ch9zw97j25emudupq63nyw24cg27h2rspfj9srp"
      val pr = PaymentRequest.read(ref)
      assert(pr.prefix == "lnbc")
      assert(pr.amount == Some(MilliSatoshi(250000000L)))
      assert(pr.paymentHash == ByteVector.fromValidHex("0001020304050607080900010203040506070809000102030405060708090102"))
      assert(pr.timestamp == 1496314658L)
      assert(pr.nodeId == PublicKey(ByteVector.fromValidHex("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")))
      assert(pr.tags.size == 3)
      assert(PaymentRequest.write(pr.sign(priv)) == ref)
    }

    {
      println("Now send $24 for an entire list of things (hashed)")
      val ref = "lnbc20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqscc6gd6ql3jrc5yzme8v4ntcewwz5cnw92tz0pc8qcuufvq7khhr8wpald05e92xw006sq94mg8v2ndf4sefvf9sygkshp5zfem29trqq2yxxz7"
      val pr = PaymentRequest.read(ref)
      assert(pr.prefix == "lnbc")
      assert(pr.amount == Some(MilliSatoshi(2000000000L)))
      assert(pr.paymentHash == ByteVector.fromValidHex("0001020304050607080900010203040506070809000102030405060708090102"))
      assert(pr.timestamp == 1496314658L)
      assert(pr.nodeId == PublicKey(ByteVector.fromValidHex("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")))
      assert(pr.tags.size == 2)
      assert(PaymentRequest.write(pr.sign(priv)) == ref)
    }

    {
      println("The same, on testnet, with a fallback address mk2QpYatsKicvFVuTAQLBryyccRXMUaGHP")
      val ref = "lntb20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsfpp3x9et2e20v6pu37c5d9vax37wxq72un98k6vcx9fz94w0qf237cm2rqv9pmn5lnexfvf5579slr4zq3u8kmczecytdx0xg9rwzngp7e6guwqpqlhssu04sucpnz4axcv2dstmknqq6jsk2l"
      val pr = PaymentRequest.read(ref)
      assert(pr.prefix == "lntb")
      assert(pr.amount == Some(MilliSatoshi(2000000000L)))
      assert(pr.paymentHash == ByteVector.fromValidHex("0001020304050607080900010203040506070809000102030405060708090102"))
      assert(pr.timestamp == 1496314658L)
      assert(pr.nodeId == PublicKey(ByteVector.fromValidHex("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")))
      assert(pr.tags.collect { case u: FallbackAddressTag => u }.size == 1)
      assert(pr.tags.size == 3)
      assert(PaymentRequest.write(pr.sign(priv)) == ref)
    }

    {
      println("On mainnet, with fallback address 1RustyRX2oai4EYYDpQGWvEL62BBGqN9T with extra routing info to go via nodes 029e03a901b85534ff1e92c43c74431f7ce72046060fcf7a95c37e148f78c77255 then 039e03a901b85534ff1e92c43c74431f7ce72046060fcf7a95c37e148f78c77255")
      val ref = "lnbc20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsfpp3qjmp7lwpagxun9pygexvgpjdc4jdj85fr9yq20q82gphp2nflc7jtzrcazrra7wwgzxqc8u7754cdlpfrmccae92qgzqvzq2ps8pqqqqqqpqqqqq9qqqvpeuqafqxu92d8lr6fvg0r5gv0heeeqgcrqlnm6jhphu9y00rrhy4grqszsvpcgpy9qqqqqqgqqqqq7qqzqj9n4evl6mr5aj9f58zp6fyjzup6ywn3x6sk8akg5v4tgn2q8g4fhx05wf6juaxu9760yp46454gpg5mtzgerlzezqcqvjnhjh8z3g2qqdhhwkj"
      val pr = PaymentRequest.read(ref)
      assert(pr.prefix == "lnbc")
      assert(pr.amount.contains(MilliSatoshi(2000000000L)))
      assert(pr.paymentHash == ByteVector.fromValidHex("0001020304050607080900010203040506070809000102030405060708090102"))
      assert(pr.timestamp == 1496314658L)
      assert(pr.nodeId == PublicKey(ByteVector.fromValidHex("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")))
      assert(pr.description == "3925b6f67e2c340036ed12093dd44e0368df1b6ea26c53dbe4811f58fd5db8c1")

      assert(pr.routingInfo == Vector(RoutingInfoTag(Vector(
          Hop(PublicKey(ByteVector.fromValidHex("029e03a901b85534ff1e92c43c74431f7ce72046060fcf7a95c37e148f78c77255")),72623859790382856L,3,0,1,20),
          Hop(PublicKey(ByteVector.fromValidHex("039e03a901b85534ff1e92c43c74431f7ce72046060fcf7a95c37e148f78c77255")),217304205466536202L,4,0,2,30)))))

      assert(pr.fallbackAddress.contains("1RustyRX2oai4EYYDpQGWvEL62BBGqN9T"))
      assert(Protocol.writeUInt64(0x0102030405060708L, ByteOrder.BIG_ENDIAN) == ByteVector.fromValidHex("0102030405060708"))
      assert(Protocol.writeUInt64(0x030405060708090aL, ByteOrder.BIG_ENDIAN) == ByteVector.fromValidHex("030405060708090a"))
      assert(pr.tags.size == 4)
      assert(PaymentRequest.write(pr.sign(priv)) == ref)
    }

    {
      println("On mainnet, with fallback (p2sh) address 3EktnHQD7RiAE6uzMj2ZifT9YgRrkSgzQX")
      val ref = "lnbc20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsfppj3a24vwu6r8ejrss3axul8rxldph2q7z9kk822r8plup77n9yq5ep2dfpcydrjwzxs0la84v3tfw43t3vqhek7f05m6uf8lmfkjn7zv7enn76sq65d8u9lxav2pl6x3xnc2ww3lqpagnh0u"
      val pr = PaymentRequest.read(ref)
      assert(pr.prefix == "lnbc")
      assert(pr.amount == Some(MilliSatoshi(2000000000L)))
      assert(pr.paymentHash == ByteVector.fromValidHex("0001020304050607080900010203040506070809000102030405060708090102"))
      assert(pr.timestamp == 1496314658L)
      assert(pr.nodeId == PublicKey(ByteVector.fromValidHex("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")))
      assert(pr.tags.size == 3)
      assert(PaymentRequest.write(pr.sign(priv)) == ref)
    }

    {
      println("On mainnet, with fallback (p2wpkh) address bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4")
      val ref = "lnbc20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsfppqw508d6qejxtdg4y5r3zarvary0c5xw7kknt6zz5vxa8yh8jrnlkl63dah48yh6eupakk87fjdcnwqfcyt7snnpuz7vp83txauq4c60sys3xyucesxjf46yqnpplj0saq36a554cp9wt865"
      val pr = PaymentRequest.read(ref)
      assert(pr.prefix == "lnbc")
      assert(pr.amount == Some(MilliSatoshi(2000000000L)))
      assert(pr.paymentHash == ByteVector.fromValidHex("0001020304050607080900010203040506070809000102030405060708090102"))
      assert(pr.timestamp == 1496314658L)
      assert(pr.nodeId == PublicKey(ByteVector.fromValidHex("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")))
      assert(pr.tags.size == 3)
      assert(PaymentRequest.write(pr.sign(priv)) == ref)
    }


    {
      println("On mainnet, with fallback (p2wsh) address bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3")
      val ref = "lnbc20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsfp4qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qvnjha2auylmwrltv2pkp2t22uy8ura2xsdwhq5nm7s574xva47djmnj2xeycsu7u5v8929mvuux43j0cqhhf32wfyn2th0sv4t9x55sppz5we8"
      val pr = PaymentRequest.read(ref)
      assert(pr.prefix == "lnbc")
      assert(pr.amount == Some(MilliSatoshi(2000000000L)))
      assert(pr.paymentHash == ByteVector.fromValidHex("0001020304050607080900010203040506070809000102030405060708090102"))
      assert(pr.timestamp == 1496314658L)
      assert(pr.nodeId == PublicKey(ByteVector.fromValidHex("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")))
      assert(pr.tags.size == 3)
      assert(PaymentRequest.write(pr.sign(priv)) == ref)
    }

    {
      println("On mainnet, with fallback (p2wsh) address bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3 and a minimum htlc cltv expiry of 12")
      val ref = "lnbc20m1pvjluezcqpvpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsfp4qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q90qkf3gd7fcqs0ewr7t3xf72ptmc4n38evg0xhy4p64nlg7hgrmq6g997tkrvezs8afs0x0y8v4vs8thwsk6knkvdfvfa7wmhhpcsxcqw0ny48"
      val pr = PaymentRequest.read(ref)
      assert(pr.prefix == "lnbc")
      assert(pr.amount == Some(MilliSatoshi(2000000000L)))
      assert(pr.paymentHash == ByteVector.fromValidHex("0001020304050607080900010203040506070809000102030405060708090102"))
      assert(pr.timestamp == 1496314658L)
      assert(pr.nodeId == PublicKey(ByteVector.fromValidHex("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")))
      assert(pr.minFinalCltvExpiry == Some(12))
      assert(pr.tags.size == 4)
      assert(PaymentRequest.write(pr.sign(priv)) == ref)
    }

    {
      println("On testnet, with lnurl")
      val ref = "lntb5m1pwx9ysvrzjqges6y6c0dn6shq2xm4qq8zdhg2te4ydm2yc3aesxf6mqs9lld4t6936ugqqqsqqqyqqqfcsqqqq86qqjqfppqs45ntpt6d9h8aeattk08ur2fqydwwp4jv9qdp68gurn8ghj7ctsdyhxy6t5wfjkv6tvdshxxmmd9amrztm5dphhy0m385ekvcenxc6r2c35xvukxefcv5mkvv34x5ekzd3ev56nyd3hxqurzepexejxxepnxscrvwfnv9nxzcn9xq6xyefhvgcxxcmyxymnserxdqqxqy9gcqpp50lu08vae3wf04pm8kly4qddqd62vpcnrmvlt2qg428y2df2awz4s4jy9nxrexsz97ws693hr5pyj7vh94hgdm0vczkv8wyg027cgxvk4fe23a5uux7tzmd3vdfea2aptnfjl5znyyx2e3yy5hmf5rvyjhgcq7349dz"
      val pr = PaymentRequest.read(ref)
      assert(pr.prefix == "lntb")
      assert(pr.lnUrlOpt.map(_.uri.toString).contains("https://api.bitrefill.com/v1/thor?q=3fc3645b439ce8e7f2553a69e5267081d96dcd340693afabe04be7b0ccd178df"))
    }
  }
}
