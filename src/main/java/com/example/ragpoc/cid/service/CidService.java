package com.example.ragpoc.cid.service;

import com.example.ragpoc.cid.entity.Cid;
import com.example.ragpoc.cid.repository.CidRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads and updates cid profiles. */
@Service
public class CidService {

  private final CidRepository cids;

  public CidService(CidRepository cids) {
    this.cids = cids;
  }

  @Transactional(readOnly = true)
  public Cid require(UUID cidId) {
    return cids
        .findById(cidId)
        .orElseThrow(() -> new IllegalStateException("Authenticated cid " + cidId + " no longer exists"));
  }

  @Transactional
  public Cid updateProfile(UUID cidId, String name, String address, String email) {
    Cid cid = require(cidId);
    cid.updateProfile(name, address, email);
    return cid;
  }

  @Transactional(readOnly = true)
  public List<Cid> list() {
    return cids.findAllByOrderByNameAsc();
  }
}
