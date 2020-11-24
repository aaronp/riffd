package riff

class RoleTest extends BaseTest {

  "Role.quarum" should {
    "return the furthest safe offset given a set of peer offsets" in {
      Role.quarum(Set(7)) shouldBe 7
      Role.quarum(Set.empty) shouldBe 0
      Role.quarum(Set(7, 9)) shouldBe 9
      Role.quarum(Set(7, 8, 9)) shouldBe 8
      Role.quarum(Set(7, 8, 9, 10)) shouldBe 9
      Role.quarum(Set(7, 7, 8, 10)) shouldBe 8
      Role.quarum(Set(7, 7, 8, 1)) shouldBe 7
    }
  }
}
